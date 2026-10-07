package com.huigu.phone10.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音消息的耳朵：按住说话，松手拿文字。
 *
 * 复用耳畔已经配好的那条识别通道（CloudSpeech），所以不需要再配一次 key；
 * 录到的原始 PCM 不落盘，转写完就丢 —— 语音消息只留文字。
 */
internal data class VoiceNoteResult(val file: java.io.File?, val text: String, val durationMs: Long)

/** 输入栏那个键的开关：再按一下就是停录，转写完把文字交回给界面。 */
internal object VoiceNoteController {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private var session: VoiceNoteSession? = null

    @Volatile
    var recording = false
        private set

    fun toggle(context: Context, onDone: (VoiceNoteResult) -> Unit) {
        val current = session
        if (current == null) {
            val fresh = VoiceNoteSession(context.applicationContext)
            if (fresh.start()) {
                session = fresh
                recording = true
            }
            return
        }
        session = null
        recording = false
        scope.launch {
            val result = current.stopAndTranscribe()
            onDone(result)
        }
    }
}

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

    /** 停录：把这段声音存成文件，同时交给耳畔的识别通道拿文字。 */
    suspend fun stopAndTranscribe(): VoiceNoteResult {
        if (!running && recorder == null) return VoiceNoteResult(null, "", 0L)
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
        val durationMs = data.size * 1000L / (SAMPLE_RATE * 2)
        if (data.size < SAMPLE_RATE / 2) return VoiceNoteResult(null, "", durationMs)
        val file = withContext(Dispatchers.IO) { runCatching { saveWav(data) }.getOrNull() }
        val text =
            withContext(Dispatchers.IO) {
                runCatching {
                    val speech = SettingsStore(context.applicationContext).load().speech
                    CloudSpeech(speech).transcribe(data)
                }.getOrDefault("")
            }
        return VoiceNoteResult(file, text, durationMs)
    }

    /** 声音本身要留住 —— 气泡上那根波形和重听都指着它。 */
    private fun saveWav(pcmBytes: ByteArray): java.io.File {
        val dir = java.io.File(context.filesDir, "voice-notes").apply { mkdirs() }
        val file = java.io.File(dir, "vn-${System.currentTimeMillis()}.wav")
        java.io.FileOutputStream(file).use { out ->
            out.write(wavHeader(pcmBytes.size))
            out.write(pcmBytes)
        }
        // 只留最近 200 条，其余的删掉。
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(200)
            ?.forEach { runCatching { it.delete() } }
        return file
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = SAMPLE_RATE * channels * bits / 8
        val out = java.io.ByteArrayOutputStream()
        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun le32(value: Long) {
            for (i in 0..3) out.write(((value shr (8 * i)) and 0xff).toInt())
        }
        fun le16(value: Int) {
            for (i in 0..1) out.write((value shr (8 * i)) and 0xff)
        }
        ascii("RIFF"); le32((36 + dataSize).toLong()); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels); le32(SAMPLE_RATE.toLong())
        le32(byteRate.toLong()); le16(channels * bits / 8); le16(bits)
        ascii("data"); le32(dataSize.toLong())
        return out.toByteArray()
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
