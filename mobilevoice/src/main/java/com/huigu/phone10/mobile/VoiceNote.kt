package com.huigu.phone10.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语音消息的耳朵：按住说话，松手拿文字。
 *
 * 复用耳畔已经配好的那条识别通道（CloudSpeech），所以不需要再配一次 key；
 * 录到的原始 PCM 不落盘，转写完就丢 —— 语音消息只留文字。
 */
internal class VoiceNoteSession(private val context: Context) {

    private val pcm = ByteArrayOutputStream()
    private var recorder: AudioRecord? = null
    private var pump: Thread? = null

    @Volatile private var running = false

    fun start(): Boolean {
        if (running) return false
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = if (min > 0) maxOf(min, SAMPLE_RATE) else SAMPLE_RATE * 2
        val record =
            runCatching {
                AudioRecord(SOURCE, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return false
        }
        recorder = record
        synchronized(pcm) { pcm.reset() }
        running = true
        runCatching { record.startRecording() }.onFailure {
            running = false
            runCatching { record.release() }
            recorder = null
            return false
        }
        pump =
            Thread {
                val buffer = ByteArray(BLOCK)
                while (running) {
                    val read = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                    if (read > 0) synchronized(pcm) { pcm.write(buffer, 0, read) }
                }
            }.also { it.start() }
        return true
    }

    /** 停录并把这段声音交给耳畔的识别通道；认不出来就返回空串。 */
    suspend fun stopAndTranscribe(): String {
        if (!running && recorder == null) return ""
        running = false
        runCatching { pump?.join(600L) }
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        pump = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        synchronized(pcm) { pcm.reset() }
        // 不到四分之一秒的声音不值得发出去
        if (data.size < SAMPLE_RATE / 2) return ""
        return withContext(Dispatchers.IO) {
            runCatching {
                val speech = SettingsStore(context.applicationContext).load().speech
                CloudSpeech(speech).transcribe(data)
            }.getOrDefault("")
        }
    }

    fun cancel() {
        running = false
        runCatching { pump?.join(300L) }
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        pump = null
        synchronized(pcm) { pcm.reset() }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val BLOCK = 4096
    }
}
