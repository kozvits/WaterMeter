package com.watermeter.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermeter.util.ExportImportManager
import com.watermeter.util.ExportImportResult
import com.watermeter.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SettingsEvent {
    data class ShowMessage(val message: String) : SettingsEvent()
    object None : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val exportImportManager: ExportImportManager
) : ViewModel() {

    val botToken = settingsManager.botToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val chatId = settingsManager.chatId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _event = MutableStateFlow<SettingsEvent>(SettingsEvent.None)
    val event: StateFlow<SettingsEvent> = _event.asStateFlow()

    fun saveBotToken(token: String) = viewModelScope.launch {
        settingsManager.saveBotToken(token.trim())
    }

    fun saveChatId(chatId: String) = viewModelScope.launch {
        settingsManager.saveChatId(chatId.trim())
    }

    fun exportData(context: Context, uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        when (val result = exportImportManager.exportToUri(context, uri)) {
            is ExportImportResult.Success -> _event.value = SettingsEvent.ShowMessage("✅ ${result.message}")
            is ExportImportResult.Error   -> _event.value = SettingsEvent.ShowMessage("❌ ${result.message}")
        }
        _isLoading.value = false
    }

    fun importData(context: Context, uri: Uri) = viewModelScope.launch {
        _isLoading.value = true
        when (val result = exportImportManager.importFromUri(context, uri)) {
            is ExportImportResult.Success -> _event.value = SettingsEvent.ShowMessage("✅ ${result.message}")
            is ExportImportResult.Error   -> _event.value = SettingsEvent.ShowMessage("❌ ${result.message}")
        }
        _isLoading.value = false
    }

    fun clearEvent() { _event.value = SettingsEvent.None }
}
