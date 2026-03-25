package com.watermeter.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val KEY_CHAT_ID   = stringPreferencesKey("telegram_chat_id")
    }

    val botToken: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_BOT_TOKEN] ?: "" }

    val chatId: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_CHAT_ID] ?: "" }

    suspend fun saveBotToken(token: String) {
        context.dataStore.edit { it[KEY_BOT_TOKEN] = token }
    }

    suspend fun saveChatId(chatId: String) {
        context.dataStore.edit { it[KEY_CHAT_ID] = chatId }
    }
}
