package com.watermeter.ml

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * На вход приходит уже ОБРЕЗАННОЕ фото (только зона одометра из MeterCameraActivity).
 * Поэтому OCR видит почти исключительно цифры — лишний текст минимален.
 *
 * Алгоритм:
 * 1. Собираем все распознанные цифровые блоки (игнорируем буквы)
 * 2. Выбираем самую длинную непрерывную цифровую последовательность
 * 3. Отрезаем дробную часть (после точки/запятой/пробела — красное поле)
 * 4. Принимаем только результат длиной 4–8 цифр
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
                        val raw = result.text
                        val value = parseReading(result)
                        cont.resume(OcrResult(meterValue = value, rawText = raw))
                    }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }

    private fun parseReading(
        result: com.google.mlkit.vision.text.Text
    ): String? {
        val candidates = mutableListOf<String>()

        // Итерируем по блокам и строкам — ML Kit возвращает иерархию
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text

                // Убираем OCR-замены и пробелы между цифрами
                val cleaned = normalizeOcr(lineText)
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                // Извлекаем числовой префикс строки (до первого нецифрового символа
                // кроме пробела перед следующей цифрой — то есть красное поле)
                extractInteger(cleaned)?.let { candidates.add(it) }
            }
        }

        if (candidates.isEmpty()) return null

        // Берём кандидата с наибольшим количеством цифр (= главный одометр)
        return candidates
            .filter { it.length in 4..8 }
            .maxByOrNull { it.length }
    }

    /**
     * Из строки вида "03469 7" или "03469.7" или "012217 74" берём только
     * первый цифровой блок — ДО пробела+цифра или ДО точки/запятой.
     *
     * Примеры:
     *   "03469.7"   → "03469"
     *   "03469 7"   → "03469"   (пробел перед красным полем)
     *   "012217 74" → "012217"
     *   "107289"    → "107289"
     *   "00228"     → "00228"
     */
    private fun extractInteger(text: String): String? {
        // Убираем пробелы внутри числа (ML Kit иногда разбивает "1 0 7 2 8 9")
        val compacted = text.replace(Regex("""(?<=\d) (?=\d)"""), "")

        // Ищем первый цифровой блок
        val match = Regex("""(\d+)""").find(compacted) ?: return null
        var digits = match.value

        // Проверяем, что за блоком не сразу идут ещё цифры через разделитель
        // "03469.7" → берём "03469", игнорируем ".7"
        // "03469 7" → берём "03469", игнорируем " 7" (красное поле)
        val afterMatch = compacted.substring(match.range.last + 1)
        if (afterMatch.startsWith(".") || afterMatch.startsWith(",") ||
            afterMatch.startsWith(" ")) {
            // Всё верно — дробь/красное поле уже отрезаны, берём только digits
        }

        return digits.ifEmpty { null }
    }

    private fun normalizeOcr(text: String): String = text
        .replace(Regex("""[Oo]"""), "0")
        .replace(Regex("""[Il|]"""), "1")
        .replace(Regex("""[Ss]"""), "5")
        .replace(Regex("""[Gg]"""), "6")
        .replace(Regex("""[Zz]"""), "2")
        .replace(Regex("""[Bb]"""), "8")
}
