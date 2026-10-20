package com.example.callscheduler.data.ai

import android.content.Context
import com.example.callscheduler.data.parser.AudioPcmDecoder
import com.example.callscheduler.data.parser.CallMetadata
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

object WhisperNative {
    init { System.loadLibrary("callwhisper") }
    external fun open(path: String): Long
    external fun run(handle: Long, audio: FloatArray): String
    external fun close(handle: Long)
}
object LocalModelLock { val mutex = Mutex() }
class OnDeviceWhisperSttEngine(private val context: Context) : SttEngine {
    override suspend fun transcribe(audioOrPcmFile: File): Result<SttResult> = withContext(Dispatchers.IO) {
        try { LocalModelLock.mutex.withLock {
            val model = ModelStore.file(context, false)
            require(model.isFile) { "설정 → 로컬 모델 관리에서 Whisper 모델을 먼저 가져오세요." }
            val pcm = File.createTempFile("whisper_", ".pcm", context.cacheDir)
            try {
                AudioPcmDecoder.decodeToPcmInfo(audioOrPcmFile, pcm).getOrThrow()
                val handle = WhisperNative.open(model.absolutePath)
                val text = StringBuilder()
                try { RandomAccessFile(pcm, "r").use { input ->
                    // Bounded three-minute blocks; process every sample, no 60-second cap.
                    while (input.filePointer < input.length()) {
                        currentCoroutineContext().ensureActive()
                        val bytes = ByteArray(minOf(16000L * 2 * 180, input.length() - input.filePointer).toInt())
                        input.readFully(bytes)
                        val samples = FloatArray(bytes.size / 2) { i ->
                            (((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()).toFloat() / 32768f
                        }
                        text.append(WhisperNative.run(handle, samples)).append('\n')
                    }
                }} finally { WhisperNative.close(handle) }
                require(text.isNotBlank()) { "인식된 음성이 없습니다." }
                Result.success(SttResult(text.toString().trim(), 0f, "WHISPER_LOCAL"))
            } finally { pcm.delete() }
        }} catch(e: CancellationException) { throw e } catch(e: Exception) { Result.failure(e) }
    }
}
class OnDeviceLiteRtExtractor(private val context: Context) : ScheduleExtractor {
    override suspend fun extractActions(metadata: CallMetadata, transcript: String): Result<List<ExtractedActionDto>> = withContext(Dispatchers.IO) {
        try { LocalModelLock.mutex.withLock {
            val model = ModelStore.file(context, true)
            require(model.isFile) { "설정 → 로컬 모델 관리에서 Gemma .task 모델을 먼저 가져오세요." }
            // Do not silently truncate long calls or ignore later corrections.
            require(transcript.length <= 2800) { "로컬 Gemma의 안전한 문맥 범위(전사문 2,800자)를 넘었습니다. 전사문을 확인하거나 혼합 모드로 다시 분석하세요. 외부로 자동 전송하지 않았습니다." }
            val options = LlmInference.LlmInferenceOptions.builder().setModelPath(model.absolutePath)
                .setMaxTokens(4096).setPreferredBackend(LlmInference.Backend.CPU).build()
            LlmInference.createFromOptions(context, options).use { engine ->
                LlmInferenceSession.createFromOptions(engine, LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(1).setTemperature(0.1f).build()).use { session ->
                    session.addQueryChunk(ScheduleJson.prompt(metadata.callDateTime.toString(), transcript))
                    Result.success(ScheduleJson.parse(session.generateResponse(), transcript))
                }
            }
        }} catch(e: CancellationException) { throw e } catch(e: Exception) { Result.failure(e) }
    }
}
