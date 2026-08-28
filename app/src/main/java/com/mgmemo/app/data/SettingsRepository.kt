package com.mgmemo.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val themeMode: String = "system",
    val editorLayout: String = "preview",
    val enableBiometric: Boolean = false,
    val aiApiUrl: String = "https://api.openai.com/v1",
    val aiApiKey: String = "",
    val aiModel: String = "gpt-4o-mini"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val EDITOR_LAYOUT = stringPreferencesKey("editor_layout")
        val ENABLE_BIOMETRIC = stringPreferencesKey("enable_biometric")
        val AI_API_URL = stringPreferencesKey("ai_api_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
            editorLayout = prefs[Keys.EDITOR_LAYOUT] ?: "preview",
            enableBiometric = prefs[Keys.ENABLE_BIOMETRIC]?.toBooleanStrictOrNull() ?: false,
            aiApiUrl = prefs[Keys.AI_API_URL] ?: "https://api.openai.com/v1",
            aiApiKey = prefs[Keys.AI_API_KEY] ?: "",
            aiModel = prefs[Keys.AI_MODEL] ?: "gpt-4o-mini"
        )
    }

    suspend fun updateThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun updateEditorLayout(layout: String) {
        context.dataStore.edit { it[Keys.EDITOR_LAYOUT] = layout }
    }

    suspend fun updateBiometric(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_BIOMETRIC] = enabled.toString() }
    }

    suspend fun updateAiConfig(url: String, key: String, model: String) {
        context.dataStore.edit {
            it[Keys.AI_API_URL] = url
            it[Keys.AI_API_KEY] = key
            it[Keys.AI_MODEL] = model
        }
    }
}
