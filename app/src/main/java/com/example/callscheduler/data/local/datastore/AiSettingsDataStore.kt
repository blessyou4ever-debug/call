package com.example.callscheduler.data.local.datastore

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

enum class EngineMode(val label: String) {
    ON_DEVICE("로컬: Whisper + Gemma (외부 전송 없음)"),
    AUTO_FALLBACK("혼합: Whisper + Gemini (전사문만 전송)"),
    CLOUD_API("외부: Gemini (음성·전사문 전송)")
}

data class CallFolderPreset(
    val label: String,
    val path: String
)

class AiSettingsDataStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai_engine_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_CUSTOM_CALL_DIR = "custom_call_dir"
        private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
        private const val KEY_PREFERRED_MODEL = "preferred_gemini_model"

        private val AUTO_BACKUP_FILES = listOf(
            "/storage/emulated/0/Download/.callscheduler_settings_backup.json",
            "/storage/emulated/0/Recordings/.callscheduler_settings_backup.json",
            "/storage/emulated/0/Recordings/Call/.callscheduler_settings_backup.json",
            "/storage/emulated/0/Recordings/TPhoneCallRecords/.callscheduler_settings_backup.json"
        )

        val DEFAULT_CALL_DIR_PRESETS = listOf(
            CallFolderPreset("삼성 기본 전화", "/storage/emulated/0/Recordings/Call"),
            CallFolderPreset("T전화·에이닷 (Recordings)", "/storage/emulated/0/Recordings/TPhoneCallRecords"),
            CallFolderPreset("T전화 (Music 폴더)", "/storage/emulated/0/Music/TPhoneCallRecords"),
            CallFolderPreset("T전화 (내장메모리 루트)", "/storage/emulated/0/TPhoneCallRecords"),
            CallFolderPreset("구형 갤럭시 Call", "/storage/emulated/0/Call")
        )

        private val modeState = MutableStateFlow(EngineMode.ON_DEVICE)
        private val apiKeyState = MutableStateFlow("")
        private val customCallDirState = MutableStateFlow("")
        private val customTreeUriState = MutableStateFlow("")
        private val preferredModelState = MutableStateFlow("gemini-2.5-flash-lite")
        private var initialized = false
    }

    init {
        if (!initialized) {
            var savedMode = prefs.getString(KEY_ENGINE_MODE, null)
            var savedKey = KeyVault.decrypt(prefs.getString(KEY_GEMINI_API_KEY, "") ?: "")
            if(savedKey.isNotBlank()) prefs.edit().putString(KEY_GEMINI_API_KEY, KeyVault.encrypt(savedKey)).apply()
            var savedDir = prefs.getString(KEY_CUSTOM_CALL_DIR, "") ?: ""
            val savedTreeUri = prefs.getString(KEY_CUSTOM_TREE_URI, "") ?: ""
            val savedModel = prefs.getString(KEY_PREFERRED_MODEL, "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite"

            if (savedKey.isBlank() && savedDir.isBlank() && savedMode == null) {
                val restored: Triple<EngineMode, String, String>? = null
                if (restored != null) {
                    savedMode = restored.first.name
                    savedKey = restored.second
                    savedDir = restored.third
                    prefs.edit()
                        .putString(KEY_ENGINE_MODE, savedMode)
                        .putString(KEY_GEMINI_API_KEY, KeyVault.encrypt(savedKey))
                        .putString(KEY_CUSTOM_CALL_DIR, savedDir)
                        .apply()
                }
            }

            val finalModeName = savedMode ?: EngineMode.ON_DEVICE.name
            modeState.value = runCatching { EngineMode.valueOf(finalModeName) }.getOrDefault(EngineMode.ON_DEVICE)
            apiKeyState.value = savedKey
            customCallDirState.value = savedDir
            customTreeUriState.value = savedTreeUri
            preferredModelState.value = savedModel.ifBlank { "gemini-2.5-flash-lite" }
            initialized = true
        }
    }

    val engineModeFlow: Flow<EngineMode> = modeState
    val apiKeyFlow: Flow<String> = apiKeyState
    val customCallDirFlow: Flow<String> = customCallDirState
    val customTreeUriFlow: Flow<String> = customTreeUriState
    val preferredModelFlow: Flow<String> = preferredModelState

    fun getCurrentMode(): EngineMode = modeState.value
    fun getCurrentApiKey(): String = apiKeyState.value
    fun getCustomCallDir(): String = customCallDirState.value
    fun getCustomTreeUri(): String = customTreeUriState.value
    fun getPreferredModel(): String = preferredModelState.value

    fun setPreferredModelSync(modelName: String) {
        val clean = modelName.removePrefix("models/").trim()
        if (clean.isNotBlank()) {
            prefs.edit().putString(KEY_PREFERRED_MODEL, clean).apply()
            preferredModelState.value = clean
        }
    }

    fun getActiveMonitoredDirs(): List<String> {
        if (customTreeUriState.value.isNotBlank()) return emptyList()
        return listOf(customCallDirState.value.trim().ifBlank { "/storage/emulated/0/Recordings/Call" })
    }

    suspend fun setEngineMode(mode: EngineMode) {
        prefs.edit().putString(KEY_ENGINE_MODE, mode.name).apply()
        modeState.value = mode
        writeAutoBackupFile()
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_GEMINI_API_KEY, KeyVault.encrypt(trimmed)).apply()
        apiKeyState.value = trimmed
        writeAutoBackupFile()
    }

    suspend fun setCustomCallDir(dirPath: String, treeUriStr: String? = null) {
        val trimmed = dirPath.trim().removeSuffix("/")
        val editor = prefs.edit().putString(KEY_CUSTOM_CALL_DIR, trimmed)
        if (treeUriStr != null) {
            editor.putString(KEY_CUSTOM_TREE_URI, treeUriStr)
            customTreeUriState.value = treeUriStr
        }
        editor.apply()
        customCallDirState.value = trimmed
        writeAutoBackupFile()
    }

    fun exportSettingsJson(): String {
        return JSONObject().apply {
            put("engine_mode", modeState.value.name)
            put("custom_call_dir", customCallDirState.value)
            put("custom_tree_uri", customTreeUriState.value)
            put("preferred_gemini_model", preferredModelState.value)
        }.toString()
    }

    suspend fun importSettingsJson(jsonStr: String): Boolean {
        return runCatching {
            val obj = JSONObject(jsonStr.trim())
            val modeName = obj.optString("engine_mode", EngineMode.ON_DEVICE.name)
            val mode = runCatching { EngineMode.valueOf(modeName) }.getOrDefault(EngineMode.ON_DEVICE)
            val key = obj.optString("gemini_api_key", "").trim()
            val dir = obj.optString("custom_call_dir", "").trim().removeSuffix("/")
            val treeUri = obj.optString("custom_tree_uri", "").trim()
            val model = obj.optString("preferred_gemini_model", "gemini-2.5-flash-lite").trim().ifBlank { "gemini-2.5-flash-lite" }

            prefs.edit()
                .putString(KEY_ENGINE_MODE, mode.name)
                .putString(KEY_GEMINI_API_KEY, KeyVault.encrypt(key))
                .putString(KEY_CUSTOM_CALL_DIR, dir)
                .putString(KEY_CUSTOM_TREE_URI, treeUri)
                .putString(KEY_PREFERRED_MODEL, model)
                .apply()

            modeState.value = mode
            apiKeyState.value = key
            customCallDirState.value = dir
            customTreeUriState.value = treeUri
            preferredModelState.value = model
            writeAutoBackupFile()
            true
        }.getOrDefault(false)
    }

    private fun writeAutoBackupFile() {
        val json = exportSettingsJson()
        val targets = mutableListOf<String>()
        val custom = customCallDirState.value.trim()
        if (custom.isNotBlank()) {
            targets.add("${custom}/.callscheduler_settings_backup.json")
        }
        targets.addAll(AUTO_BACKUP_FILES)

        for (path in targets) {
            runCatching {
                val file = File(path)
                if (file.parentFile?.exists() == true) {
                    file.writeText(json, Charsets.UTF_8)
                }
            }
        }
    }

    private fun tryRestoreFromAutoBackupFile(): Triple<EngineMode, String, String>? {
        for (path in AUTO_BACKUP_FILES) {
            val file = File(path)
            if (file.exists() && file.canRead() && file.length() > 0L) {
                val parsed = runCatching {
                    val obj = JSONObject(file.readText(Charsets.UTF_8))
                    val mode = runCatching {
                        EngineMode.valueOf(obj.optString("engine_mode", EngineMode.ON_DEVICE.name))
                    }.getOrDefault(EngineMode.ON_DEVICE)
                    val key = obj.optString("gemini_api_key", "")
                    val dir = obj.optString("custom_call_dir", "")
                    if (key.isNotBlank() || dir.isNotBlank()) Triple(mode, key, dir) else null
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }
}
