package com.watermeter.ui.meters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermeter.data.model.Meter
import com.watermeter.data.model.MeterWithReadings
import com.watermeter.data.model.Reading
import com.watermeter.data.repository.MeterRepository
import com.watermeter.util.DateUtils
import com.watermeter.util.SettingsManager
import com.watermeter.util.TelegramResult
import com.watermeter.util.TelegramSender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    object TelegramSuccess : UiEvent()
}

@HiltViewModel
class MetersViewModel @Inject constructor(
    private val repository: MeterRepository,
    private val telegramSender: TelegramSender,
    private val settingsManager: SettingsManager
) : ViewModel() {

    // ── State ────────────────────────────────────────────────────────────────

    val meters: StateFlow<List<MeterWithReadings>> = repository
        .getMetersWithReadings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _event = MutableStateFlow<UiEvent?>(null)
    val event: StateFlow<UiEvent?> = _event.asStateFlow()

    // ── Meter CRUD ────────────────────────────────────────────────────────────

    fun insertMeter(meter: Meter) = viewModelScope.launch {
        repository.insertMeter(meter)
    }

    fun updateMeter(meter: Meter) = viewModelScope.launch {
        repository.updateMeter(meter)
    }

    fun deleteMeter(meter: Meter) = viewModelScope.launch {
        repository.deleteMeter(meter)
    }

    // ── Reading CRUD ──────────────────────────────────────────────────────────

    fun insertReading(reading: Reading) = viewModelScope.launch {
        repository.insertReading(reading)
    }

    fun deleteReading(reading: Reading) = viewModelScope.launch {
        repository.deleteReading(reading)
    }

    // ── Telegram ──────────────────────────────────────────────────────────────

    fun sendToTelegram() = viewModelScope.launch {
        _isLoading.value = true

        val botToken = settingsManager.botToken.first()
        val chatId   = settingsManager.chatId.first()

        // Берём последние показания каждого счётчика за текущий месяц
        val from = DateUtils.currentMonthStart()
        val to   = DateUtils.currentMonthEnd()
        val latestReadings = repository.getLatestReadingsForPeriod(from, to)
            .associateBy { it.meterId }

        val currentMeters = meters.value
        val payload: List<Pair<Meter, Reading?>> = currentMeters.map { mwr ->
            Pair(mwr.meter, latestReadings[mwr.meter.id] ?: mwr.lastReading)
        }

        when (val result = telegramSender.sendMonthlyReport(botToken, chatId, payload)) {
            is TelegramResult.Success -> {
                _event.value = UiEvent.TelegramSuccess
            }
            is TelegramResult.Error -> {
                _event.value = UiEvent.ShowSnackbar(result.message)
            }
        }

        _isLoading.value = false
    }

    fun clearEvent() {
        _event.value = null
    }
}
