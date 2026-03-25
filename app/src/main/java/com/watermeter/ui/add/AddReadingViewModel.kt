package com.watermeter.ui.add

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermeter.data.model.Meter
import com.watermeter.data.model.Reading
import com.watermeter.data.repository.MeterRepository
import com.watermeter.ml.MeterOcrHelper
import com.watermeter.ml.OcrResult
import com.watermeter.util.ImageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AddUiState {
    object Idle : AddUiState()
    object Processing : AddUiState()          // OCR или сохранение фото
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

    /** URI выбранного/снятого изображения */
    private val _imageUri = MutableStateFlow<Uri?>(null)
    val imageUri: StateFlow<Uri?> = _imageUri.asStateFlow()

    /** URI сохранённого (обрезанного) фото в галерее */
    private var savedPhotoUri: Uri? = null

    fun setImageUri(uri: Uri, context: Context) {
        _imageUri.value = uri
        runOcr(uri, context)
    }

    private fun runOcr(uri: Uri, context: Context) = viewModelScope.launch {
        _uiState.value = AddUiState.Processing
        try {
            // Параллельно: OCR + обрезка и сохранение фото в галерею
            val ocrResult = ocrHelper.recognize(context, uri)
            savedPhotoUri = ImageUtils.cropAndSaveToGallery(context, uri)
            _uiState.value = AddUiState.OcrDone(ocrResult)
        } catch (e: Exception) {
            _uiState.value = AddUiState.OcrError("Распознавание не удалось. Введите данные вручную.")
        }
    }

    /**
     * Сохраняет показания. Если счётчик с таким serialNumber уже есть — добавляет
     * показание к нему. Если нет — создаёт новый счётчик.
     */
    fun saveReading(
        serialNumber: String,
        meterName: String,
        valueStr: String
    ) = viewModelScope.launch {
        val value = valueStr.replace(",", ".").toDoubleOrNull()
        if (value == null) {
            _uiState.value = AddUiState.Error("Неверный формат показаний. Используйте цифры, например: 147.832")
            return@launch
        }
        if (serialNumber.isBlank()) {
            _uiState.value = AddUiState.Error("Укажите номер счётчика")
            return@launch
        }

        _uiState.value = AddUiState.Processing

        try {
            // Ищем существующий счётчик по серийному номеру
            val existingMeters = repository.getMetersWithReadings()
            // Используем suspend collect через first()
            var existingMeterId: Long? = null
            existingMeters.collect { list ->
                existingMeterId = list.firstOrNull {
                    it.meter.serialNumber.equals(serialNumber.trim(), ignoreCase = true)
                }?.meter?.id
                return@collect // берём только первое значение
            }

            val meterId = existingMeterId ?: run {
                // Создаём новый счётчик
                val newMeter = Meter(
                    serialNumber = serialNumber.trim(),
                    name = meterName.trim().ifBlank { serialNumber.trim() }
                )
                repository.insertMeter(newMeter)
            }

            val reading = Reading(
                meterId = meterId,
                value = value,
                photoPath = savedPhotoUri?.toString()
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
        savedPhotoUri = null
    }
}
