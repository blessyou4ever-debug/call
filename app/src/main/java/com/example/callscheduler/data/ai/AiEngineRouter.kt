package com.example.callscheduler.data.ai

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import com.example.callscheduler.data.local.datastore.EngineMode
import com.example.callscheduler.data.parser.AudioPcmDecoder
import com.example.callscheduler.data.parser.CallMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.coroutines.resume

data class SttResult(
    val transcript: String,
    val confidence: Float,
    val engineName: String
)

data class ExtractedActionDto(
    val actionType: String = "APPOINTMENT",
    val title: String,
    val description: String,
    val startDateTimeIso: String? = null,
    val endDateTimeIso: String? = null,
    val location: String? = null
)

interface SttEngine {
    suspend fun transcribe(audioOrPcmFile: File): Result<SttResult>
}

interface ScheduleExtractor {
    suspend fun extractActions(metadata: CallMetadata, transcript: String): Result<List<ExtractedActionDto>>
}

class CloudGeminiEngine(private val settingsDataStore: AiSettingsDataStore) : SttEngine, ScheduleExtractor {

    companion object {
        // 특정 세대(예: 3.x 계열)의 무료 쿼터가 소진(HTTP 429)되더라도,
        // 별도 무료 쿼터 버킷을 가진 Lite / 2.0 / 2.5 / 1.5 세대로 즉시 우회하도록 세대별로 다변화합니다.
        private val CROSS_FAMILY_CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite-preview",
            "gemini-2.5-flash",
            "gemini-1.5-flash",
            "gemini-3.8-flash",
            "gemini-3.6-flash"
        )
        private val USE_MODEL_REGEX = Regex("""use\s+models/([a-zA-Z0-9._-]+)""", RegexOption.IGNORE_CASE)
        private val SUGGESTED_MODEL_REGEX = Regex("""models/([a-zA-Z0-9._-]+)""")
    }

    private fun isAudioCapableModelName(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.contains("gemini") || !lower.contains("flash")) return false
        val unsupportedKeywords = listOf("tts", "image", "vision", "embedding", "aqa", "bisect", "thinking")
        return unsupportedKeywords.none { lower.contains(it) }
    }

    /**
     * 모델의 쿼터 버킷 패밀리(세대)를 판별합니다.
     * 예: gemini-3.6-flash에서 HTTP 429(쿼터 초과)가 발생하면, 같은 3.x 일반 Flash 버킷(3.8-flash, 3.5-flash)을
     * 중복 호출해 시간을 낭비하지 않고 즉시 다른 쿼터 버킷(2.5-flash-lite, 2.0-flash, 1.5-flash 등)으로 건너뜁니다!
     */
    private fun getQuotaBucketFamily(modelName: String): String {
        val m = modelName.lowercase()
        return when {
            m.startsWith("gemini-3") && m.contains("lite") -> "family_3_lite"
            m.startsWith("gemini-3") -> "family_3_std"
            m.startsWith("gemini-2.5") && m.contains("lite") -> "family_25_lite"
            m.startsWith("gemini-2.5") -> "family_25_std"
            m.startsWith("gemini-2.0") && m.contains("lite") -> "family_20_lite"
            m.startsWith("gemini-2.0") -> "family_20_std"
            m.startsWith("gemini-1.5") -> "family_15"
            else -> m
        }
    }

    suspend fun diagnoseApiConnection(apiKeyOverride: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = (apiKeyOverride ?: settingsDataStore.getCurrentApiKey()).trim()
            if (apiKey.isBlank()) {
                return@withContext Result.success("❌ Gemini API 키가 비어 있습니다. 먼저 API 키를 입력하고 저장해 주세요.")
            }

            val testPayload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", "연결 테스트입니다. OK 라고만 답하세요."))
                        )
                    )
                )
            }.toString()

            return@withContext try {
                val (reply, winningModel) = postGeminiJsonWithFallback(apiKey, testPayload, maxAttempts = 6)
                settingsDataStore.setPreferredModelSync(winningModel)
                Result.success(
                    "✅ Gemini API 쿼터 정상 모델 탐색 및 고정 완료!\n" +
                        "• 현재 무료 할당량이 살아있는 모델: ${winningModel}\n" +
                        "• 테스트 응답: ${reply.trim().take(40)}\n" +
                        "• 이제 429 쿼터 오류 없이 [${winningModel}] 모델로 즉시 분석합니다."
                )
            } catch (t: Throwable) {
                Result.success(
                    "❌ Gemini API 모든 모델 호출 실패\n• ${t.message}\n\n" +
                        "💡 [해결 팁] Google AI Studio에서 해당 프로젝트의 무료 일일 할당량(Quota)이 모두 소진된 상태입니다. " +
                        "AI Studio에서 [새 프로젝트(New Project)]를 만들어 새 API 키를 발급받거나, 상단 모드를 [온디바이스 우선 (무료·보안)]으로 선택해 주세요."
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun transcribe(audioOrPcmFile: File): Result<SttResult> = withContext(Dispatchers.IO) {
        try {
            val apiKey = settingsDataStore.getCurrentApiKey()
            require(apiKey.isNotBlank()) { "Gemini API 키가 설정되지 않았습니다." }
            require(audioOrPcmFile.exists() && audioOrPcmFile.length() > 0L) {
                "오디오 파일이 존재하지 않거나 비어 있습니다: ${audioOrPcmFile.absolutePath}"
            }
            require(audioOrPcmFile.length() <= 18L * 1024L * 1024L) {
                "녹음 파일 크기가 너무 큽니다 (${audioOrPcmFile.length() / 1024}KB)."
            }

            val mimeType = resolveAudioMimeType(audioOrPcmFile.extension)
            val audioBytes = audioOrPcmFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val firstAttempt = runCatching {
                requestAudioTranscription(apiKey, mimeType, base64Audio)
            }

            val (responseText, usedModel) = if (firstAttempt.isSuccess) {
                firstAttempt.getOrThrow()
            } else {
                val firstErr = firstAttempt.exceptionOrNull()
                val errMsg = firstErr?.message ?: ""
                if ((errMsg.contains("HTTP 400") || errMsg.contains("INVALID_ARGUMENT")) && audioOrPcmFile.length() <= 1_500_000L) {
                    val wavAttempt = runCatching { AudioPcmDecoder.transcodeToWavBytes(audioOrPcmFile).getOrThrow() }
                    if (wavAttempt.isSuccess) {
                        val wavBase64 = Base64.encodeToString(wavAttempt.getOrThrow(), Base64.NO_WRAP)
                        requestAudioTranscription(apiKey, "audio/wav", wavBase64)
                    } else {
                        throw firstErr ?: IllegalStateException("Gemini 오디오 STT 호출 실패")
                    }
                } else {
                    throw firstErr ?: IllegalStateException("Gemini 오디오 STT 호출 실패")
                }
            }

            val parsedTranscript = unwrapTranscriptJson(responseText)
            require(parsedTranscript.isNotBlank()) { "Gemini API(${usedModel})가 빈 텍스트를 반환했습니다." }

            Result.success(
                SttResult(
                    transcript = parsedTranscript,
                    confidence = 0f,
                    engineName = "CLOUD_GEMINI (${usedModel})"
                )
            )
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "Gemini 오디오 STT 오류 (${t.javaClass.simpleName})"))
        }
    }

    private suspend fun requestAudioTranscription(
        apiKey: String,
        mimeType: String,
        base64Audio: String
    ): Pair<String, String> {
        val parts = JSONArray().apply {
            put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", mimeType)
                        .put("data", base64Audio)
                )
            )
            put(
                JSONObject().put(
                    "text",
                    "이 통화 녹음 오디오의 대화 내용을 한국어로 빠짐없이 받아쓰기(STT)해 주세요. 부연 설명 없이 실제 통화 대화 내용 텍스트만 그대로 출력하세요."
                )
            )
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        }.toString()

        return postGeminiJsonWithFallback(apiKey, requestJson, maxAttempts = 5)
    }

    private fun unwrapTranscriptJson(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            when {
                cleaned.startsWith("[") -> {
                    val arr = JSONArray(cleaned)
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        val t = item?.optString("transcript") ?: arr.optString(i)
                        if (!t.isNullOrBlank()) {
                            if (sb.isNotEmpty()) sb.append(" ")
                            sb.append(t.trim())
                        }
                    }
                    sb.toString().ifBlank { cleaned }
                }
                cleaned.startsWith("{") -> {
                    JSONObject(cleaned).optString("transcript", cleaned).trim()
                }
                else -> cleaned
            }
        }.getOrDefault(cleaned).trim()
    }

    override suspend fun extractActions(
        metadata: CallMetadata,
        transcript: String
    ): Result<List<ExtractedActionDto>> = withContext(Dispatchers.IO) {
        try {
            val apiKey = settingsDataStore.getCurrentApiKey()
            require(apiKey.isNotBlank()) { "Gemini API 키가 설정되지 않았습니다." }

            val prompt = ScheduleJson.prompt(metadata.callDateTime.toString(), transcript)
            val plainRequest = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
            }.toString()

            val (textContent, _) = postGeminiJsonWithFallback(apiKey, plainRequest, maxAttempts = 3)
            Result.success(ScheduleJson.parse(textContent, transcript))
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "일정 추출 오류"))
        }
    }

    private suspend fun postGeminiJsonWithFallback(
        apiKey: String,
        requestJson: String,
        maxAttempts: Int = 5
    ): Pair<String, String> {
        val preferred = settingsDataStore.getPreferredModel().ifBlank { "gemini-2.5-flash-lite" }

        val initialPool = LinkedHashSet<String>().apply {
            add(preferred)
            addAll(CROSS_FAMILY_CANDIDATE_MODELS)
        }

        val triedModels = mutableSetOf<String>()
        val exhaustedFamilies = mutableSetOf<String>()
        val errorTraces = mutableListOf<String>()
        val queue = ArrayDeque<String>(initialPool)

        while (queue.isNotEmpty() && triedModels.size < maxAttempts) {
            val modelName = queue.removeFirst().removePrefix("models/")
            val family = getQuotaBucketFamily(modelName)
            // 이미 HTTP 429(쿼터 초과)가 발생한 세대(예: family_3_std)의 다른 모델들은 즉시 건너뛰어 다른 세대 모델을 시도합니다!
            if (family in exhaustedFamilies) continue
            if (!triedModels.add(modelName)) continue

            try {
                val responseText = callSingleModel(apiKey, modelName, requestJson)
                settingsDataStore.setPreferredModelSync(modelName)
                return Pair(responseText, modelName)
            } catch (e: GeminiRetryableHttpException) {
                errorTraces.add("${modelName}(HTTP ${e.statusCode}: ${e.cleanReason.take(75)})")

                if (e.statusCode == 401 || e.statusCode == 403 || e.cleanReason.contains("API_KEY_INVALID")) {
                    break
                }

                if (e.statusCode == 429 || e.cleanReason.contains("RESOURCE_EXHAUSTED")) {
                    // 해당 모델 패밀리의 쿼터가 소진되었으므로 같은 패밀리는 모두 스킵!
                    exhaustedFamilies.add(family)
                    continue
                }

                val explicitRecommended = USE_MODEL_REGEX.find(e.errorBody)?.groupValues?.getOrNull(1)
                    ?: SUGGESTED_MODEL_REGEX.findAll(e.errorBody)
                        .map { it.groupValues[1] }
                        .firstOrNull { it != modelName && isAudioCapableModelName(it) }

                if (!explicitRecommended.isNullOrBlank() && explicitRecommended !in triedModels) {
                    queue.addFirst(explicitRecommended)
                    continue
                }
            } catch (t: Throwable) {
                errorTraces.add("${modelName}(${t.message?.take(75) ?: t.javaClass.simpleName})")
            }
        }

        // 서버의 활성 모델 목록에서 아직 시도하지 않은 세대의 모델이 있다면 1회 추가 탐색
        val discovered = discoverAvailableFlashModels(apiKey).firstOrNull {
            it !in triedModels && getQuotaBucketFamily(it) !in exhaustedFamilies
        }
        if (discovered != null) {
            try {
                val responseText = callSingleModel(apiKey, discovered, requestJson)
                settingsDataStore.setPreferredModelSync(discovered)
                return Pair(responseText, discovered)
            } catch (t: Throwable) {
                errorTraces.add("${discovered}(${t.message?.take(75)})")
            }
        }

        val allQuotaExhausted = errorTraces.isNotEmpty() && errorTraces.all { it.contains("429") || it.contains("RESOURCE_EXHAUSTED") }
        val traceSummary = if (errorTraces.isNotEmpty()) {
            errorTraces.take(3).joinToString(" / ")
        } else {
            "알 수 없는 네트워크 또는 API 오류"
        }

        val quotaHint = if (allQuotaExhausted) {
            "\n💡 현재 입력된 API 키의 Google 무료 할당량(Quota)이 초과되었습니다. Google AI Studio에서 [새 프로젝트(New Project)]로 API 키를 새로 발급받거나 [온디바이스 우선] 모드를 사용해 주세요."
        } else {
            ""
        }
        throw IllegalStateException("Gemini API 호출 실패 — ${traceSummary}${quotaHint}")
    }

    private fun extractCleanGoogleErrorMessage(rawErrorBody: String): String {
        if (rawErrorBody.isBlank()) return "응답 본문 없음"
        return runCatching {
            val errObj = JSONObject(rawErrorBody).optJSONObject("error")
            if (errObj != null) {
                val status = errObj.optString("status", "")
                val msg = errObj.optString("message", rawErrorBody)
                if (status.isNotBlank()) "[${status}] ${msg}" else msg
            } else {
                rawErrorBody.replace(Regex("\\s+"), " ").take(180)
            }
        }.getOrDefault(rawErrorBody.replace(Regex("\\s+"), " ").take(180))
    }

    private inner class GeminiRetryableHttpException(
        val statusCode: Int,
        val modelName: String,
        val errorBody: String
    ) : IllegalStateException("HTTP ${statusCode} (${modelName}): ${extractCleanGoogleErrorMessage(errorBody)}") {
        val cleanReason: String = extractCleanGoogleErrorMessage(errorBody)
    }

    private fun callSingleModel(apiKey: String, modelName: String, requestJson: String): String {
        val cleanModel = modelName.removePrefix("models/")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${cleanModel}:generateContent?key=${apiKey}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7_000
            readTimeout = 16_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        conn.outputStream.use { os ->
            os.write(requestJson.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
            throw GeminiRetryableHttpException(responseCode, cleanModel, errBody)
        }
        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }

        val root = JSONObject(responseStr)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("응답에 candidates가 없습니다 (${cleanModel})")
        if (candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason", "UNKNOWN")
            throw IllegalStateException("응답이 차단되었거나 비어 있습니다 (사유: ${blockReason})")
        }

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("응답에 content.parts가 없습니다 (${cleanModel})")

        var fallbackText = ""
        for (i in 0 until parts.length()) {
            val partObj = parts.optJSONObject(i) ?: continue
            val text = partObj.optString("text", "")
            val isThought = partObj.optBoolean("thought", false)
            if (text.isNotBlank()) {
                if (!isThought) {
                    return text
                }
                fallbackText = text
            }
        }
        if (fallbackText.isNotBlank()) return fallbackText
        throw IllegalStateException("모델(${cleanModel}) 응답 파트에서 텍스트를 찾을 수 없습니다.")
    }

    private fun discoverAvailableFlashModels(apiKey: String): List<String> = runCatching {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
        }
        if (conn.responseCode !in 200..299) return@runCatching emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val models = JSONObject(body).optJSONArray("models") ?: return@runCatching emptyList()
        val found = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val name = m.optString("name", "").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (isAudioCapableModelName(name) && methods.contains("generateContent")) {
                found.add(name)
            }
        }
        // 무료 쿼터가 가장 넉넉한 lite 모델을 우선 배치
        found.sortedWith(compareByDescending<String> { it.contains("lite") }.thenByDescending { it })
    }.getOrDefault(emptyList())

}

class HybridCallAiProcessor(
    private val settingsDataStore: AiSettingsDataStore,
    private val onDeviceStt: SttEngine,
    private val onDeviceExtractor: ScheduleExtractor,
    private val cloudEngine: CloudGeminiEngine
) {
    suspend fun process(audioFile: File, metadata: CallMetadata, overrideMode: EngineMode? = null,
        forceCloudOnly: Boolean = false, onProgressUpdate: (suspend (String) -> Unit)? = null
    ): Result<Triple<String, String, List<ExtractedActionDto>>> = try {
        val mode = if (forceCloudOnly) EngineMode.CLOUD_API else overrideMode ?: settingsDataStore.getCurrentMode()
        // AUTO_FALLBACK is retained as a stored enum name for compatibility; now explicitly hybrid.
        val cloudAudio = mode == EngineMode.CLOUD_API
        val cloudText = mode != EngineMode.ON_DEVICE
        if (cloudText) require(settingsDataStore.getCurrentApiKey().isNotBlank()) { "Gemini API 키를 먼저 저장하세요." }
        onProgressUpdate?.invoke(if (cloudAudio) "Gemini로 녹음 전송 및 전사 중" else "Whisper 로컬 모델로 전체 녹음 전사 중 (외부 전송 없음)")
        val stt = (if (cloudAudio) cloudEngine else onDeviceStt).transcribe(audioFile).getOrThrow()
        onProgressUpdate?.invoke(if (cloudText) "전사문을 Gemini로 보내 일정 분석 중" else "Gemma 로컬 모델로 일정 분석 중")
        val actions = (if (cloudText) cloudEngine else onDeviceExtractor).extractActions(metadata, stt.transcript).getOrElse {
            throw IllegalStateException("음성 인식은 완료되었지만 일정 분석에 실패했습니다: ${it.message}\n\n[전사문]\n${stt.transcript}", it)
        }
        Result.success(Triple(stt.transcript, stt.engineName, actions))
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { Result.failure(e) }
}

private fun resolveAudioMimeType(extension: String): String = when(extension.lowercase()) {
    "m4a", "m4u", "mp4" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "amr" -> "audio/amr"
    "3gp" -> "audio/3gpp"
    else -> "application/octet-stream"
}
