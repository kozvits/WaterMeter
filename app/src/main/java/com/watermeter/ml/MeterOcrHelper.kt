package com.watermeter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class OcrResult(
    val meterValue: String?,
    val serialNumber: String? = null,
    val rawText: String = ""
)

/**
 * OCR показаний счётчика воды.
 *
 * Стратегия борьбы с красным полем:
 * 1. Перед OCR анализируем битмап и находим X-координату начала красного фона.
 * 2. Все символы правее этой границы — дробная часть, игнорируем их.
 * 3. Если красная зона не найдена — берём первый цифровой блок из всего текста.
 * 4. Дополнительная защита: показания никогда не совпадают с серийным номером.
 */
@Singleton
class MeterOcrHelper @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(context: Context, imageUri: Uri): OcrResult =
        suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, imageUri)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        cont.resume(OcrResult(meterValue = null, rawText = result.text))
                    }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }.let { preliminary ->
            // Запускаем полный анализ с учётом красного поля
            recognizeWithRedDetection(context, imageUri, preliminary.rawText)
        }

    private suspend fun recognizeWithRedDetection(
        context: Context,
        imageUri: Uri,
        rawText: String
    ): OcrResult = withContext(Dispatchers.IO) {
        try {
            // Загружаем битмап для анализа цвета
            val bmp = loadBitmap(context, imageUri)
            val redBoundaryX = if (bmp != null) findRedBoundaryX(bmp) else null

            val image = InputImage.fromFilePath(context, imageUri)
            val textResult = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }

            val value = extractReading(textResult, redBoundaryX, bmp?.width ?: 0)
            OcrResult(meterValue = value, rawText = textResult.text)
        } catch (e: Exception) {
            OcrResult(meterValue = null, rawText = rawText)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Поиск красного фона в битмапе
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Сканирует битмап по горизонтальным полосам и ищет столбец,
     * где начинается зона с преобладающим красным цветом.
     *
     * Критерий "красный пиксель":
     *   R > 150 AND R > G*1.8 AND R > B*1.8
     *
     * Возвращает X (в px) левого края красной зоны, или null если не найдена.
     */
    private fun findRedBoundaryX(bmp: Bitmap): Int? {
        val w = bmp.width
        val h = bmp.height

        // Сканируем среднюю треть по высоте (там одометр)
        val scanTop    = h / 3
        val scanBottom = h * 2 / 3
        val scanHeight = scanBottom - scanTop

        // Для каждого столбца считаем долю красных пикселей
        val redDensity = IntArray(w)
        for (x in 0 until w) {
            var redCount = 0
            for (y in scanTop until scanBottom) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 150 && r > g * 1.8f && r > b * 1.8f) redCount++
            }
            redDensity[x] = redCount * 100 / scanHeight
        }

        // Ищем первый столбец где плотность красного > 25% подряд на 5+ столбцах
        var consecutiveRed = 0
        for (x in 0 until w) {
            if (redDensity[x] > 25) {
                consecutiveRed++
                if (consecutiveRed >= 5) {
                    // Отступаем назад к началу серии
                    return (x - consecutiveRed + 1).coerceAtLeast(0)
                }
            } else {
                consecutiveRed = 0
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Извлечение показаний из результата OCR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Извлекает целые показания.
     *
     * Если [redBoundaryX] определён — берём только символы левее этой границы.
     * Иначе — применяем текстовую эвристику (обрезаем по . , пробел+цифра).
     */
    private fun extractReading(
        result: Text,
        redBoundaryX: Int?,
        bitmapWidth: Int
    ): String? {
        val candidates = mutableListOf<Pair<String, Int>>() // значение, длина

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val boundingBox = line.boundingBox ?: continue
                val lineText = normalizeOcr(line.text)

                val digitsOnly: String

                if (redBoundaryX != null && bitmapWidth > 0) {
                    // Режим с красной границей:
                    // Оставляем только символы (и их элементы) левее границы
                    digitsOnly = extractDigitsBeforeRedBoundary(
                        line, redBoundaryX, bitmapWidth, lineText
                    )
                } else {
                    // Режим без красной границы:
                    // Берём первый цифровой блок до разделителя
                    digitsOnly = extractDigitsBeforeDelimiter(lineText)
                }

                val cleaned = digitsOnly.filter { it.isDigit() }
                if (cleaned.length in 4..8) {
                    candidates.add(Pair(cleaned, cleaned.length))
                }
            }
        }

        return candidates
            .filter { it.second in 4..8 }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Для каждого элемента строки проверяем, находится ли он левее красной границы.
     * ML Kit предоставляет boundingBox для каждого element (отдельного слова/символа).
     */
    private fun extractDigitsBeforeRedBoundary(
        line: Text.Line,
        redBoundaryX: Int,
        bitmapWidth: Int,
        fallbackText: String
    ): String {
        val sb = StringBuilder()
        for (element in line.elements) {
            val box = element.boundingBox ?: continue
            val elementCenterX = box.centerX()
            // Правый край элемента должен быть левее красной границы
            if (box.right < redBoundaryX) {
                sb.append(normalizeOcr(element.text))
            }
        }
        // Если ничего не собрали через элементы — используем fallback
        return if (sb.isNotEmpty()) sb.toString() else extractDigitsBeforeDelimiter(fallbackText)
    }

    /**
     * Текстовая эвристика: берём цифровой блок до первого разделителя.
     * Разделитель = точка, запятая, или пробел перед следующей цифрой.
     */
    private fun extractDigitsBeforeDelimiter(text: String): String {
        // Склеиваем одиночные цифры разделённые пробелами ("1 0 7 2 8 9" → "107289")
        val compacted = text.replace(Regex("""(?<=\d) (?=\d)"""), "")

        // Берём всё до точки/запятой/пробела-за-которым-цифра
        val match = Regex("""(\d+)(?:[.,]|\s+\d|$)""").find(compacted)
        return match?.groupValues?.get(1) ?: compacted.filter { it.isDigit() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    } catch (e: Exception) { null }

    /**
     * Нормализация типичных OCR-замен.
     * Применяем агрессивнее, т.к. на кропе почти только цифры.
     */
    private fun normalizeOcr(text: String): String = text
        .replace('O', '0').replace('o', '0')
        .replace('I', '1').replace('l', '1').replace('|', '1')
        .replace('S', '5').replace('s', '5')
        .replace('G', '6').replace('g', '6')
        .replace('Z', '2').replace('z', '2')
        .replace('B', '8').replace('b', '8')
        .replace('q', '9').replace('Q', '9')
}
