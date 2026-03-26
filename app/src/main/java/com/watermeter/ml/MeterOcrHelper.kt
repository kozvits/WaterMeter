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
 * OCR для распознавания ЦЕЛЫХ показаний счётчика воды.
 *
 * Ключевые правила:
 *  1. Берём только цифры ДО красного поля (дробная часть отбрасывается).
 *  2. Показания — это 5–8 подряд идущих цифр.
 *  3. Из нескольких кандидатов выбираем самый длинный блок — он и есть главный одометр.
 *
 * Примеры:
 *   "03469.7 m³"      → "03469"
 *   "01221 774"       → "01221"   (красное поле = " 774")
 *   "012217,4"        → "012217"  не верно — тогда → "01221"  (5 цифр до запятой)
 *   "107289"          → "107289"
 *   "00228"           → "00228"
 *   "1 0 7 2 8 9"     → "107289"  (OCR с пробелами между цифрами)
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
                        val value = extractIntegerReading(raw)
                        cont.resume(OcrResult(meterValue = value, rawText = raw))
                    }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }

    private fun extractIntegerReading(rawText: String): String? {
        // Шаг 1: нормализация OCR-ошибок
        val normalized = normalizeOcr(rawText)

        // Шаг 2: убираем пробелы между одиночными цифрами
        // ("1 0 7 2 8 9" → "107289")
        val compacted = normalized.replace(Regex("""(?<=\d) (?=\d)"""), "")

        // Шаг 3: разбиваем текст на токены по строкам
        val candidates = mutableListOf<String>()

        compacted.lines().forEach { line ->
            // Паттерн: 5–8 цифр подряд, после которых:
            //   — ничего (конец числа)
            //   — точка/запятая с дробью  → отрезаем
            //   — пробел + цифры           → это красное поле, отрезаем
            //   — буква (м³, m³)           → конец числа, ОК
            val regex = Regex("""(?<!\d)(\d{5,8})(?:[.,]\d+|(?=\s+\d)|\s*${'$'}|\s*[^\d]|${'$'})""")
            regex.findAll(line).forEach { match ->
                candidates.add(match.groupValues[1])
            }
        }

        if (candidates.isEmpty()) return null

        // Шаг 4: выбираем наилучший кандидат
        // Приоритет: длина 6–8 цифр (типовой одометр) > 5 цифр
        return candidates
            .filter { it.length in 5..8 }
            // Исключаем явные серийники: строки, где после числа идут буквы вплотную
            .maxByOrNull { it.length }
    }

    /**
     * Типичные OCR-замены:
     *  O → 0, I/l → 1 — только между цифрами
     */
    private fun normalizeOcr(text: String): String = text
        .replace(Regex("""(?<=\d)[Oo](?=\d)"""), "0")
        .replace(Regex("""(?<=\d)[Il](?=\d)"""), "1")
}
