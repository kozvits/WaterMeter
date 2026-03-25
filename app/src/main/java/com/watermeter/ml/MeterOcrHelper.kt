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
    val meterValue: String?,    // Показания, напр. "147.832"
    val serialNumber: String?,  // Номер счётчика, напр. "ВСХ-15-00384521"
    val rawText: String
)

@Singleton
class MeterOcrHelper @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Распознаёт текст на изображении по URI.
     * Вызывается из корутины — приостанавливается до получения результата ML Kit.
     */
    suspend fun recognize(context: Context, imageUri: Uri): OcrResult =
        suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, imageUri)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        cont.resume(parseText(result.text))
                    }
                    .addOnFailureListener { e ->
                        cont.resumeWithException(e)
                    }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Извлекает из сырого OCR-текста:
     *  - показания счётчика (5–8 цифр, возможно с дробной частью)
     *  - серийный номер (буквенно-цифровая комбинация)
     *
     * Алгоритм:
     * 1. Нормализуем текст: убираем лишние пробелы, заменяем O→0, I→1 в числах.
     * 2. Ищем кандидатов на показания через регулярное выражение.
     * 3. Ищем серийный номер — сначала по паттерну «буквы-цифры», затем эвристически.
     */
    private fun parseText(rawText: String): OcrResult {
        val normalized = normalizeOcr(rawText)

        // ── Показания счётчика ────────────────────────────────────────────────
        // Примеры: "00147832", "147.832", "147,832", "0014 783 2"
        val readingRegex = Regex(
            """(?<!\d)(\d{1,3}(?:\s\d{3})*[.,]\d{1,3}|\d{5,8})(?!\d)"""
        )
        val meterValue = readingRegex.findAll(normalized)
            .map { it.value.replace(" ", "").replace(",", ".") }
            .filter { candidate ->
                candidate.filter { it.isDigit() }.length in 5..8
            }
            // Числа с дробной частью приоритетнее целых — они точнее соответствуют показаниям
            .maxByOrNull { candidate -> if (candidate.contains('.')) 10 else 0 }

        // ── Серийный номер ────────────────────────────────────────────────────
        // Паттерн 1: кириллические/латинские буквы + цифры (ВСХ-15-00384521)
        val serialRegex1 = Regex(
            """([А-ЯA-Z]{2,4}[-\s]?\d{2}[-\s]?\d{4,8})""",
            RegexOption.IGNORE_CASE
        )
        // Паттерн 2: явный маркер "№", "No", "SN"
        val serialRegex2 = Regex(
            """(?:№|[Nn][o°]?|[Ss][/]?[Nn])[:\s]*([A-ZА-Яa-zа-я0-9]{6,12})"""
        )
        val serialNumber = serialRegex1.find(normalized)?.groupValues?.getOrNull(1)
            ?: serialRegex2.find(normalized)?.groupValues?.getOrNull(1)
            ?: heuristicSerial(normalized.lines())

        return OcrResult(meterValue, serialNumber?.trim(), rawText)
    }

    /**
     * Исправляет типичные OCR-ошибки в числах: буква O → 0, буква I → 1.
     */
    private fun normalizeOcr(text: String): String {
        return text
            .replace(Regex("""(?<=[0-9])[Oo](?=[0-9])"""), "0")
            .replace(Regex("""(?<=[0-9])[Ii](?=[0-9])"""), "1")
    }

    /**
     * Эвристика: строка, содержащая 7+ цифр и не слишком длинная —
     * вероятный кандидат на серийный номер.
     */
    private fun heuristicSerial(lines: List<String>): String? =
        lines.firstOrNull { line ->
            line.count { it.isDigit() } >= 7 && line.length in 7..20
        }
}
