package com.watermeter.ml

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val meterValue: String?,    // Только целые м³, напр. "3469"
    val serialNumber: String?,  // Из штрихкода или QR-кода
    val rawText: String
)

@Singleton
class MeterOcrHelper @Inject constructor() {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner = BarcodeScanning.getClient()

    /**
     * Основной метод: параллельно запускает OCR текста и сканирование штрихкода/QR.
     * Показания — только целые числа (до красного поля).
     * Серийный номер — из штрихкода или QR-кода.
     */
    suspend fun recognize(context: Context, imageUri: Uri): OcrResult {
        val image = InputImage.fromFilePath(context, imageUri)
        val rawText = runTextOcr(image)
        val serialFromCode = runBarcodeScanner(image)
        val meterValue = extractIntegerReading(rawText)
        return OcrResult(
            meterValue = meterValue,
            serialNumber = serialFromCode,
            rawText = rawText
        )
    }

    private suspend fun runTextOcr(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            textRecognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    private suspend fun runBarcodeScanner(image: InputImage): String? =
        suspendCancellableCoroutine { cont ->
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val best = barcodes
                        .sortedByDescending { barcode ->
                            when (barcode.format) {
                                Barcode.FORMAT_QR_CODE  -> 3
                                Barcode.FORMAT_CODE_128 -> 2
                                Barcode.FORMAT_CODE_39  -> 1
                                else                    -> 0
                            }
                        }
                        .firstOrNull()
                    cont.resume(best?.rawValue?.trim())
                }
                .addOnFailureListener { cont.resume(null) }
        }

    /**
     * Извлекает показания счётчика как ЦЕЛОЕ число.
     *
     * Примеры реальных счётчиков:
     *  "03469.7 m³"  → "03469"   (Zenner: красное поле = дробь)
     *  "01221 774"   → "01221"   (БелЦЕННЕР: красные разряды после пробела)
     *  "107289"      → "107289"  (WPD 50: без дроби)
     *  "00228"       → "00228"   (простой счётчик)
     */
    private fun extractIntegerReading(rawText: String): String? {
        val normalized = normalizeOcrDigits(rawText)

        // Ищем 5–8 цифр, отрезаем всё после точки/запятой/пробела+цифры
        val readingRegex = Regex("""(?<![.\d])(\d{5,8})(?:[.,\s]\d+)?(?!\d)""")

        return readingRegex.findAll(normalized)
            .map { it.groupValues[1] }
            .filter { it.length in 5..8 }
            .maxByOrNull { it.length }
    }

    private fun normalizeOcrDigits(text: String): String = text
        .replace(Regex("""(?<=\d)[Oo](?=\d)"""), "0")
        .replace(Regex("""(?<=\d)[Il](?=\d)"""), "1")
}
