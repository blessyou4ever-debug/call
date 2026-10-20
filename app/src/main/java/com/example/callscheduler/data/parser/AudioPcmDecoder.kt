package com.example.callscheduler.data.parser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class DecodedPcmInfo(
    val pcmFile: File,
    val sampleRate: Int,
    val channelCount: Int,
    val durationSeconds: Float
)

object AudioPcmDecoder {
    private const val TARGET_SAMPLE_RATE = 16000

    // Stream supported Android codecs into bounded 16 kHz mono PCM on disk.
    fun decodeToPcmInfo(inputAudioFile: File, outputPcmFile: File): Result<DecodedPcmInfo> = runCatching {
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        var samplesOut=0L
        try {
            extractor.setDataSource(inputAudioFile.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
                ?: error("오디오 트랙이 없습니다.")
            extractor.selectTrack(track);val format=extractor.getTrackFormat(track)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=android.media.AudioFormat.ENCODING_PCM_16BIT
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!);codec=decoder
            decoder.configure(format,null,null,0);decoder.start()
            var inputEnd=false;var outputEnd=false;val info=MediaCodec.BufferInfo()
            var previous=0f;var sourceIndex=0L;var nextPosition=0.0
            var lastOutput=System.nanoTime()
            outputPcmFile.outputStream().buffered(65536).use { out ->
                while(!outputEnd) {
                    check(System.nanoTime()-lastOutput<30_000_000_000L){"오디오 디코딩 응답 시간이 초과되었습니다."}
                    if(!inputEnd) {
                        val index=decoder.dequeueInputBuffer(10000)
                        if(index>=0){val buffer=decoder.getInputBuffer(index)!!;buffer.clear();val n=extractor.readSampleData(buffer,0)
                            if(n<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true}
                            else {decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0);extractor.advance()}}
                    }
                    val index=decoder.dequeueOutputBuffer(info,10000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){val f=decoder.outputFormat
                        rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                        require(rate>0 && channels>0 && encoding in listOf(android.media.AudioFormat.ENCODING_PCM_16BIT,android.media.AudioFormat.ENCODING_PCM_FLOAT)) {"지원하지 않는 PCM 형식"}
                    } else if(index>=0){
                        try {
                            val buffer=decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            val frameBytes=channels*(if(encoding==android.media.AudioFormat.ENCODING_PCM_FLOAT)4 else 2)
                            while(buffer.remaining()>=frameBytes){var current=0f;repeat(channels){current+=if(encoding==android.media.AudioFormat.ENCODING_PCM_FLOAT)buffer.float else buffer.short/32768f};current/=channels
                                if(sourceIndex==0L)previous=current
                                while(nextPosition<=sourceIndex){
                                    val t=(nextPosition-(sourceIndex-1)).coerceIn(0.0,1.0).toFloat()
                                    val value=((previous+(current-previous)*t).coerceIn(-1f,1f)*32767).toInt()
                                    out.write(value and 255);out.write((value shr 8) and 255);samplesOut++
                                    require(samplesOut<=16000L*7200){"2시간을 초과하는 녹음은 분할해 주세요."}
                                    nextPosition+=rate.toDouble()/16000
                                };previous=current;sourceIndex++
                            }
                            if(info.size>0)lastOutput=System.nanoTime()
                            outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        } finally {decoder.releaseOutputBuffer(index,false)}
                    }
                }
            }
            require(samplesOut>0){"디코딩된 음성이 없습니다."}
            DecodedPcmInfo(outputPcmFile,16000,1,samplesOut/16000f)
        } finally {codec?.let{runCatching{it.stop()};it.release()};extractor.release()}
    }

    fun transcodeToWavBytes(inputAudioFile: File): Result<ByteArray> = runCatching {
        val tempPcm = File.createTempFile("gemini_wav_", ".pcm")
        try {
            val decoded = decodeToPcmInfo(inputAudioFile, tempPcm).getOrThrow()
            val pcmBytes = decoded.pcmFile.readBytes()
            require(pcmBytes.isNotEmpty()) { "디코딩된 PCM 데이터가 비어 있습니다." }

            val channels = 1
            val sampleRate = TARGET_SAMPLE_RATE
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)
            val dataSize = pcmBytes.size
            val totalChunkSize = 36 + dataSize

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalChunkSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }.array()

            val out = ByteArray(44 + dataSize)
            System.arraycopy(header, 0, out, 0, 44)
            System.arraycopy(pcmBytes, 0, out, 44, dataSize)
            out
        } finally {
            runCatching { tempPcm.delete() }
        }
    }

}
