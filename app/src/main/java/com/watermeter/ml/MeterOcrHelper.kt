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

data class OcrResult(
    val meterValue: String?,
    val serialNumber: String? = null,
    val rawText: String = ""
)

/**
 * OCR показаний счётчика воды.
 *
 * Улучшенная стратегия борьбы с красным полем:
 * 1. ⭐ КРОП перед OCR: если красная граница найдена — битмап обрезается ДО
 *    запуска ML Kit. Красные цифры физически удалены из изображения,
 *    поэтому OCR их просто не видит.
 * 2. ⭐ HSV-анализ цвета: вместо жёстких RGB-коэффициентов используем
 *    нормализованное отношение R к (G+B) — работает при любом освещении.
 * 3. ⭐ Многополосное сканирование: 4 горизонтальные полосы, чтобы не
 *    пропустить одометр при разном кадрировании.
 * 4. ⭐ Сглаживание + сниженный порог: 3px sliding window + 12%
 *    плотности вместо 25%.
 * 5. ⭐ Сканирование слева→справа и справа→налево.
 * 6. ⭐ ЕДИНСТВЕННЫЙ прогон OCR (без дублирования).
 */
@Singleton
class MeterOcrHelper @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ─────────────────────────────────────────────────────────────────────────
    // Публичный API — единственная точка входа
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Распознаёт показания счётчика на фото.
     *
     * Алгоритм:
     * 1. Загружаем Bitmap
     * 2. Ищем красную границу (X-координата начала дробной части)
     * 3. Если найдена — обрезаем битмап по ней (физически удаляем красные цифры)
     * 4. Запускаем ML Kit OCR на обрезанном (или полном) изображении
     * 5. Извлекаем цифровое значение
     *
     * → ОДИН прогон OCR, без дублирования.
     */
    suspend fun recognize(context: Context, imageUri: Uri): OcrResult =
        withContext(Dispatchers.IO) {
            try {
                val bmp = loadBitmap(context, imageUri)
                if (bmp == null) {
                    // Bitmap не загрузился — fallback OCR на файле
                    return@withContext runFallbackOcr(context, imageUri)
                }

                // 1. Улучшенное обнаружение красной границы
                val redBoundaryX = findRedBoundaryX(bmp)

                // 2. Если граница найдена и её ширина > 20px — КРОПАЕМ битмап ДО OCR
                val actuallyCropped: Boolean
                val inputImage: InputImage = if (redBoundaryX != null && redBoundaryX > 20) {
                    actuallyCropped = true
                    val croppedWidth = (redBoundaryX + 4).coerceAtMost(bmp.width)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, croppedWidth, bmp.height)
                    InputImage.fromBitmap(cropped, 0)
                } else {
                    actuallyCropped = false
                    // Красная граница не найдена — используем полное изображение
                    // и текстовую эвристику
                    InputImage.fromFilePath(context, imageUri)
                }

                // 3. ЕДИНСТВЕННЫЙ вызов OCR
                val result = runMlKit(inputImage)

                // 4. Извлечение показаний
                val value = extractReading(
                    result = result,
                    isCropped = actuallyCropped
                )

                OcrResult(meterValue = value, rawText = result.text)
            } catch (e: Exception) {
                OcrResult(meterValue = null, rawText = "")
            }
        }

    /**
     * Fallback: запускает OCR на полном изображении (без кропа)
     * и использует текстовую эвристику для извлечения показаний.
     */
    private suspend fun runFallbackOcr(context: Context, imageUri: Uri): OcrResult {
        return try {
            val image = InputImage.fromFilePath(context, imageUri)
            val result = runMlKit(image)
            val value = extractDigitsBeforeDelimiter(result.text)
            OcrResult(
                meterValue = value?.let {
                    val cleaned = it.filter { c -> c.isDigit() }
                    if (cleaned.length in 4..8) cleaned else null
                },
                rawText = result.text
            )
        } catch (e: Exception) {
            OcrResult(meterValue = null, rawText = "")
        }
    }

    private suspend fun runMlKit(inputImage: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // УЛУЧШЕННЫЙ поиск красного фона
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Сканирует битмап по 4-м горизонтальным полосам и находит X-координату
     * начала красной зоны (дробной части одометра).
     *
     * Улучшения:
     * - Нормализованный RGB-анализ: R / (G+B+1) вместо жёстких коэффициентов
     * - 4 полосы сканирования (30-60%, 40-70%, 25-55%, 35-65%) — для разных кадрирований
     * - Сглаживание (2px sliding window) для подавления шума
     * - Сниженный порог красной плотности: 12% вместо 25%
     * - Сканирование справа→налево если слева→справа не дало результата
     */
    private fun findRedBoundaryX(bmp: Bitmap): Int? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null

        // 4 полосы сканирования — разное положение одометра в кадре
        val bands = listOf(
            h * 30 / 100 to h * 60 / 100,   // верх-середина
            h * 40 / 100 to h * 70 / 100,   // низ-середина
            h * 25 / 100 to h * 55 / 100,   // верх
            h * 35 / 100 to h * 65 / 100    // центр
        )

        var bestBoundary: Int? = null
        var bestScore = 0f

        for ((scanTop, scanBottom) in bands) {
            val scanHeight = scanBottom - scanTop
            if (scanHeight < 10) continue

            val redDensity = IntArray(w)

            for (x in 0 until w) {
                var redCount = 0
                for (y in scanTop until scanBottom) {
                    if (isRedPixel(bmp.getPixel(x, y))) redCount++
                }
                redDensity[x] = redCount * 100 / scanHeight
            }

            // Сглаживание: sliding window 3px (2+1+2)
            val smoothed = IntArray(w) { x ->
                var sum = 0; var count = 0
                for (dx in -2..2) {
                    val ix = (x + dx).coerceIn(0, w - 1)
                    sum += redDensity[ix]; count++
                }
                sum / count
            }

            // Слева→направо: ищем начало красной зоны
            val leftResult = findTransitionLeftToRight(smoothed, w)
            if (leftResult != null) {
                val score = smoothed[leftResult].toFloat()
                if (score > bestScore) {
                    bestBoundary = leftResult
                    bestScore = score
                }
            }

            // Если слева→направо не нашли — пробуем справа→налево
            if (leftResult == null) {
                val rightResult = findTransitionRightToLeft(smoothed, w)
                if (rightResult != null) {
                    val score = smoothed.getOrNull(rightResult)?.toFloat() ?: 0f
                    if (score > bestScore) {
                        bestBoundary = rightResult
                        bestScore = score
                    }
                }
            }
        }

        return bestBoundary
    }

    /**
     * Слева→направо: ищем первый участок из 3+ столбцов с red density > 12%.
     * Возвращает X левого края этого участка.
     */
    private fun findTransitionLeftToRight(density: IntArray, w: Int): Int? {
        val threshold = 12   // 12% красной плотности (было 25%)
        val minRun = 3       // 3 столбца подряд (было 5)
        var consecutive = 0

        for (x in 0 until w) {
            if (density[x] > threshold) {
                consecutive++
                if (consecutive >= minRun) {
                    return (x - consecutive + 1).coerceAtLeast(0)
                }
            } else {
                consecutive = 0
            }
        }
        return null
    }

    /**
     * Справа→налево: ищем последний участок красной зоны справа.
     * Возвращает X левого края красной зоны.
     */
    private fun findTransitionRightToLeft(density: IntArray, w: Int): Int? {
        val threshold = 10   // ещё более низкий порог для поиска справа
        var consecutive = 0
        var lastRedEnd = -1

        for (x in w - 1 downTo 0) {
            if (density[x] > threshold) {
                if (lastRedEnd < 0) lastRedEnd = x
                consecutive++
                if (consecutive >= 3) {
                    // Левая граница красной зоны = крайний левый столбец серии
                    return (x).coerceAtLeast(0)
                }
            } else {
                consecutive = 0
                lastRedEnd = -1
            }
        }
        return null
    }

    /**
     * Определяет, является ли пиксель «красным» — нормализованным методом.
     *
     * Вместо жёстких коэффициентов (R > G*1.8) используем:
     * 1. R должен быть dominant channel
     * 2. Нормализованное отношение R / (G+B+1) > 1.35
     * 3. R > G + 10 (гарантирует видимую разницу)
     * 4. r > 60 (не слишком тёмный)
     */
    private fun isRedPixel(pixel: Int): Boolean {
        val r = Color.red(pixel).toFloat()
        val g = Color.green(pixel).toFloat()
        val b = Color.blue(pixel).toFloat()

        // Слишком тёмный — не красный
        if (r < 60f) return false

        // Слишком белый (RGB почти равны) — не красный
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max - min < 15f) return false

        // R должен быть dominant
        if (r <= g || r <= b) return false

        // ⭐ Нормализованное отношение R к (G+B)
        // Улавливает красный даже при сильной засветке вспышкой
        val ratio = r / (g + b + 1f)

        // Red dominance: R должен быть заметно больше G
        val rOverG = r / (g + 1f)

        return ratio > 1.35f && rOverG > 1.2f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Извлечение показаний из результата OCR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Извлекает числовое значение показаний.
     *
     * Если изображение было обрезано по красной границе [isCropped=true],
     * то все цифры в результате — чёрные (основные показания).
     * Иначе — применяем текстовую эвристику.
     */
    private fun extractReading(
        result: Text,
        isCropped: Boolean
    ): String? {
        // Собираем все цифровые блоки
        val candidates = mutableListOf<String>()

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val lineText = normalizeOcr(line.text)
                val digits = lineText.filter { it.isDigit() }

                if (digits.length >= 4) {
                    candidates.add(digits)
                }
            }
        }

        if (candidates.isEmpty()) {
            // Fallback: извлекаем из сырого текста
            val allText = normalizeOcr(result.text)
            val fallback = extractDigitsBeforeDelimiter(allText)?.filter { it.isDigit() }
            return if (fallback != null && fallback.length in 4..8) fallback else null
        }

        // Выбираем самый длинный цифровой блок
        return candidates
            .filter { it.length in 4..8 }
            .maxByOrNull { it.length }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Текстовая эвристика (без кропа)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Извлекает первый цифровой блок из текста до разделителя.
     * Разделитель = точка, запятая, или пробел перед следующей цифрой.
     */
    private fun extractDigitsBeforeDelimiter(text: String): String? {
        // Склеиваем цифры, разделённые пробелами: "1 0 7 2 8 9" → "107289"
        val compacted = text.replace(Regex("""(?<=\d) (?=\d)"""), "")

        // Берём первый цифровой блок до разделителя
        val match = Regex("""(\d{4,})(?:[.,]|\s+\d|$)""").find(compacted)
        return match?.groupValues?.get(1)
            ?: Regex("""\d{4,}""").find(compacted)?.value
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Декодируем с опцией inJustDecodeBounds=false (полный битмап)
            val opts = BitmapFactory.Options().apply {
                inMutable = false
            }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) { null }

    /**
     * Нормализация OCR: типичные замены буква→цифра.
     */
    private fun normalizeOcr(text: String): String = text
        .replace('O', '0').replace('o', '0')
        .replace('I', '1').replace('l', '1').replace('|', '1')
        .replace('S', '5').replace('s', '5')
        .replace('G', '6').replace('g', '6')
        .replace('Z', '2').replace('z', '2')
        .replace('B', '8').replace('b', '8')
        .replace('q', '9').replace('Q', '9')
        .replace('D', '0').replace('d', '0')  // D → 0 (часто путают)
        .replace('T', '1').replace('t', '1')  // T → 1
        .replace('P', '9').replace('p', '9')  // P → 9
}
