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

    // meterId is passed via Navigation SafeArgs
    private val meterId: Long = savedStateHandle["meterId"] ?: -1L

    val meterWithReadings: StateFlow<MeterWithReadings?> = repository
        .getMeterWithReadings(meterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _event = MutableStateFlow<String?>(null)
    val event: StateFlow<String?> = _event.asStateFlow()

    fun deleteReading(reading: Reading) = viewModelScope.launch {
        repository.deleteReading(reading)
    }

    fun updateReading(reading: Reading) = viewModelScope.launch {
        repository.updateReading(reading)
    }

    fun clearEvent() { _event.value = null }
}
