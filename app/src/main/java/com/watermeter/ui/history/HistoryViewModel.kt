package com.watermeter.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermeter.data.model.MeterWithReadings
import com.watermeter.data.model.Reading
import com.watermeter.data.repository.MeterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: MeterRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // meterId приходит как Int из nav_graph (argType="integer")
    val meterId: Long = (savedStateHandle.get<Int>("meterId") ?: -1).toLong()

    val meterWithReadings: StateFlow<MeterWithReadings?> = repository
        .getMeterWithReadings(meterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun deleteReading(reading: Reading) = viewModelScope.launch {
        repository.deleteReading(reading)
        _snackbarMessage.value = "Показание удалено"
    }

    fun updateReading(reading: Reading) = viewModelScope.launch {
        repository.updateReading(reading)
        _snackbarMessage.value = "Показание обновлено"
    }

    fun addReading(value: Double, date: Long = System.currentTimeMillis()) = viewModelScope.launch {
        if (meterId < 0) return@launch
        repository.insertReading(
            Reading(meterId = meterId, value = value, date = date)
        )
        _snackbarMessage.value = "Показание добавлено"
    }

    fun clearSnackbar() { _snackbarMessage.value = null }
}
