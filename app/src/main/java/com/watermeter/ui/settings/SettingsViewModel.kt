package com.watermeter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watermeter.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager
) : ViewModel() {

    val botToken = settingsManager.botToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val chatId = settingsManager.chatId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun saveBotToken(token: String) = viewModelScope.launch {
        settingsManager.saveBotToken(token.trim())
    }

    fun saveChatId(chatId: String) = viewModelScope.launch {
        settingsManager.saveChatId(chatId.trim())
    }
}
