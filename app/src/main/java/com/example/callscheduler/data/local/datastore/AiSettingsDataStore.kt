package com.example.callscheduler.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ai_engine_settings")

enum class EngineMode(val label: String) {
    ON_DEVICE("온디바이스 우선 (무료·보안)"),
    AUTO_FALLBACK("하이브리드 (인식률 저하 시 클라우드 전환)"),
    CLOUD_API("클라우드 API 고정 (최고 인식률)")
}

class AiSettingsDataStore(private val context: Context) {
    companion object {
        private val KEY_ENGINE_MODE = stringPreferencesKey("engine_mode")
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val engineModeFlow: Flow<EngineMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_ENGINE_MODE] ?: EngineMode.AUTO_FALLBACK.name
        runCatching { EngineMode.valueOf(raw) }.getOrDefault(EngineMode.AUTO_FALLBACK)
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY] ?: ""
    }

    suspend fun setEngineMode(mode: EngineMode) {
        context.dataStore.edit { it[KEY_ENGINE_MODE] = mode.name }
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        context.dataStore.edit { it[KEY_GEMINI_API_KEY] = apiKey.trim() }
    }
}
