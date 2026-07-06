package com.watermeter.ui.add

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.watermeter.data.model.Meter
import com.watermeter.data.model.Reading
import com.watermeter.data.repository.MeterRepository
import com.watermeter.ml.MeterOcrHelper
import com.watermeter.ml.OcrResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import javax.inject.Inject
import kotlin.coroutines.resume

sealed class AddUiState {
    object Idle : AddUiState()
    object Processing : AddUiState()
    data class OcrDone(val result: OcrResult) : AddUiState()
    data class OcrError(val message: String) : AddUiState()
    object Saved : AddUiState()
    data class Error(val message: String) : AddUiState()
}

@HiltViewModel
class AddReadingViewModel @Inject constructor(
    private val repository: MeterRepository,
    private val ocrHelper: MeterOcrHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddUiState>(AddUiState.Idle)
    val uiState: StateFlow<AddUiState> = _uiState.asStateFlow()

    private val _imageUri = MutableStateFlow<Uri?>(null)
    val imageUri: StateFlow<Uri?> = _imageUri.asStateFlow()

    private val _selectedDate = MutableStateFlow(System.currentTimeMillis())
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()

    private val _scannedBarcode = MutableStateFlow<String?>(null)
    val scannedBarcode: StateFlow<String?> = _scannedBarcode.asStateFlow()

    // URI файла, сохранённого MeterCameraActivity
    private var photoFileUri: Uri? = null

    // Серийный номер — обновляется из Fragment при сканировании QR
    private var _currentSerialNumber: String = ""

    fun onSerialNumberScanned(serial: String) {
        _currentSerialNumber = serial
    }

    fun onDateSelected(year: Int, month: Int, dayOfMonth: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        _selectedDate.value = cal.timeInMillis
    }

    /**
     * Вызывается когда MeterCameraActivity вернула URI готового снимка.
     * Запускает OCR и параллельно сохраняет обрезанную копию в галерею.
     */
    fun setImageUri(uri: Uri, context: Context) {
        _imageUri.value = uri
        photoFileUri = uri
        runOcr(uri, context)
    }

    /**
     * Обработка изображения из галереи: параллельно запускает OCR (показания)
     * и сканирование штрихкода (серийный номер).
     */
    fun processGalleryImage(uri: Uri, context: Context) {
        _imageUri.value = uri
        photoFileUri = uri
        _scannedBarcode.value = null

        launch {
            val barcodeText = scanBarcode(uri, context)
            if (!barcodeText.isNullOrBlank()) {
                _scannedBarcode.value = barcodeText
            }
        }

        runOcr(uri, context)
    }

    private suspend fun scanBarcode(uri: Uri, context: Context): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bmp == null) return null

            val image = InputImage.fromBitmap(bmp, 0)
            val scanner = BarcodeScanning.getClient()
            suspendCancellableCoroutine { cont ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        cont.resume(barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue)
                    }
                    .addOnFailureListener { cont.resume(null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun runOcr(uri: Uri, context: Context) = viewModelScope.launch {
        _uiState.value = AddUiState.Processing
        try {
            val ocrResult = ocrHelper.recognize(context, uri)

            // Защита: если OCR вернул значение == серийному номеру счётчика,
            // это ложное срабатывание (показания попали в зону серийника или наоборот).
            // В этом случае возвращаем пустое значение — пользователь введёт вручную.
            val safeResult = if (isReadingEqualsSerial(ocrResult.meterValue)) {
                ocrResult.copy(meterValue = null)
            } else {
                ocrResult
            }

            _uiState.value = AddUiState.OcrDone(safeResult)
        } catch (e: Exception) {
            _uiState.value = AddUiState.OcrError("Распознавание не удалось — введите вручную.")
        }
    }

    /** Возвращает true если распознанные показания совпадают с уже введённым серийным номером */
    private fun isReadingEqualsSerial(meterValue: String?): Boolean {
        if (meterValue.isNullOrBlank()) return false
        // Сравниваем только цифры (игнорируем дефисы, пробелы в серийнике)
        val serialDigits = _currentSerialNumber.filter { it.isDigit() }
        val readingDigits = meterValue.filter { it.isDigit() }
        if (serialDigits.isBlank() || readingDigits.isBlank()) return false
        // Считаем совпадением если показания — подстрока серийника или наоборот,
        // либо полное совпадение цифровых частей
        return serialDigits == readingDigits ||
               serialDigits.contains(readingDigits) ||
               readingDigits.contains(serialDigits)
    }

    fun saveReading(
        serialNumber: String,
        meterName: String,
        valueStr: String,
        date: Long = System.currentTimeMillis()
    ) = viewModelScope.launch {
        // Принимаем только положительные числа (целые или дробные)
        val valueClean = valueStr.trim().replace(",", ".").trimEnd('.')
        val value = valueClean.toDoubleOrNull()

        if (value == null || value <= 0) {
            _uiState.value = AddUiState.Error("Введите показания — положительное число, например: 3469 или 3469.5")
            return@launch
        }
        if (serialNumber.isBlank()) {
            _uiState.value = AddUiState.Error("Укажите номер счётчика")
            return@launch
        }

        _uiState.value = AddUiState.Processing

        try {
            val allMeters = repository.getMetersWithReadings().first()
            val existingMeterId: Long? = allMeters.firstOrNull {
                it.meter.serialNumber.equals(serialNumber.trim(), ignoreCase = true)
            }?.meter?.id

            val meterId = existingMeterId ?: run {
                val newMeter = Meter(
                    serialNumber = serialNumber.trim(),
                    name = meterName.trim().ifBlank { serialNumber.trim() }
                )
                repository.insertMeter(newMeter)
            }

            val reading = Reading(
                meterId = meterId,
                value = value,
                date = date,
                photoPath = photoFileUri?.toString()
            )
            repository.insertReading(reading)
            _uiState.value = AddUiState.Saved
        } catch (e: Exception) {
            _uiState.value = AddUiState.Error("Ошибка сохранения: ${e.localizedMessage}")
        }
    }

    fun resetState() {
        _uiState.value = AddUiState.Idle
        _imageUri.value = null
        _selectedDate.value = System.currentTimeMillis()
        _scannedBarcode.value = null
        photoFileUri = null
        _currentSerialNumber = ""
    }
}
