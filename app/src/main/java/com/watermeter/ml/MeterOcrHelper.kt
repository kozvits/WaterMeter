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
    val meterValue: String?,  // Только целые м³, напр. "3469"
    val serialNumber: String? = null, // Не используется здесь — берётся из сканера
    val rawText: String = ""
)

/**
 * OCR для распознавания ПОКАЗАНИЙ счётчика с фотографии.
 * Серийный номер считывается отдельно через BarcodeScannerActivity (QR/штрихкод).
 *
 * Возвращает только целые числа:
 *   "03469.7 m³"  → "03469"
 *   "01221 774"   → "01221"  (красное поле = дробная часть)
 *   "107289"      → "107289"
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
                    .addOnFailureListener { e ->
                        cont.resumeWithException(e)
                    }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }

    /**
     * Извлекает показания как ЦЕЛОЕ число (до красного поля/дробной части).
     *
     * Логика:
     * 1. Нормализуем OCR-ошибки (O→0, I→1)
     * 2. Ищем блоки из 5–8 цифр
     * 3. Если за числом идёт дробная часть — отрезаем её
     * 4. Берём самое длинное совпадение (главный одометр)
     */
    private fun extractIntegerReading(rawText: String): String? {
        val normalized = normalizeOcr(rawText)

        // Захватываем только целую часть: до точки/запятой/пробела перед дробью
        val regex = Regex("""(?<![.\d])(\d{5,8})(?:[.,\s]\d+)?(?!\d)""")

        return regex.findAll(normalized)
            .map { it.groupValues[1] }
            .filter { it.length in 5..8 }
            .maxByOrNull { it.length }
    }

    private fun normalizeOcr(text: String): String = text
        .replace(Regex("""(?<=\d)[Oo](?=\d)"""), "0")
        .replace(Regex("""(?<=\d)[Il](?=\d)"""), "1")
}
