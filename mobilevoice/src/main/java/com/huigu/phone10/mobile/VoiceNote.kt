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
data class VoiceNoteResult(val file: java.io.File?, val text: String, val durationMs: Long)

/** 输入栏那个键的开关：再按一下就是停录，转写完把文字交回给界面。 */
/** 系统认的附件写法：id 放完整路径，标签体留空。语音气泡就靠它出现。 */
private val VOICE_QUOTE = '\u0022'

fun voiceAttachmentTag(file: java.io.File): String =
    "<attachment id=" + VOICE_QUOTE + file.absolutePath + VOICE_QUOTE +
        " filename=" + VOICE_QUOTE + file.name + VOICE_QUOTE +
        " type=" + VOICE_QUOTE + "audio/wav" + VOICE_QUOTE +
        " size=" + VOICE_QUOTE + file.length() + VOICE_QUOTE + "></attachment>"

object VoiceNoteController {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private var session: VoiceNoteSession? = null

    /** 界面要看得见的三件事：录着、正在认、上一次为什么没成。 */
    val recording = androidx.compose.runtime.mutableStateOf(false)
    val transcribing = androidx.compose.runtime.mutableStateOf(false)
    val lastError = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** 刚刚处理过一次按住说话 —— 界面据此把紧跟着的那一下点击跳过。 */
    @Volatile var lastTouchHandledAt: Long = 0L

    fun toggle(context: Context, onDone: (VoiceNoteResult) -> Unit) {
        val current = session
        if (current == null) {
            lastError.value = null
            val permitted =
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!permitted) {
                val denied = "没拿到麦克风权限，去系统设置里给 ave 打开"
                lastError.value = denied
                say(context, denied)
                return
            }
            val fresh = VoiceNoteSession(context.applicationContext)
            if (fresh.start()) {
                session = fresh
                recording.value = true
                say(context, "录音中，说完再长按一下")
            } else {
                val busy = "麦克风打不开，可能被别的应用占着"
                lastError.value = busy
                say(context, busy)
            }
            return
        }
        session = null
        recording.value = false
        transcribing.value = true
        say(context, "认字中…")
        scope.launch {
            val result = current.stopAndTranscribe()
            transcribing.value = false
            val problem =
                when {
                    result.text.isNotBlank() -> null
                    current.lastProblem != null -> current.lastProblem
                    result.durationMs < 300 -> "太短了，没听见什么"
                    current.peak < 300 -> "没听到声音：麦克风被挡住了？"
                    else -> "没认出来：识别服务是不是还没填 key"
                }
            lastError.value = problem
            problem?.let { say(context, it) }
            onDone(result)
        }
    }

    /** 按住就开始录；已经在录就当成功。 */
    fun begin(context: Context): Boolean {
        lastTouchHandledAt = System.currentTimeMillis()
        if (session != null) return true
        lastError.value = null
        val permitted =
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            val denied = "没拿到麦克风权限，去系统设置里给 ave 打开"
            lastError.value = denied
            say(context, denied)
            return false
        }
        val fresh = VoiceNoteSession(context.applicationContext)
        if (fresh.start()) {
            session = fresh
            recording.value = true
            say(context, "录音中，松手就发")
            return true
        }
        val busy = fresh.lastProblem ?: "麦克风打不开"
        lastError.value = busy
        say(context, busy)
        return false
    }

    /** 松手就停：文字可能有，也可能没有 —— 声音一定留着。 */
    fun end(context: Context, onDone: (VoiceNoteResult) -> Unit) {
        lastTouchHandledAt = System.currentTimeMillis()
        val current = session ?: return
        session = null
        recording.value = false
        transcribing.value = true
        say(context, "认字中…")
        scope.launch {
            val result = current.stopAndTranscribe()
            transcribing.value = false
            val problem =
                when {
                    result.text.isNotBlank() -> null
                    current.lastProblem != null -> current.lastProblem
                    result.durationMs < 300 -> "太短了，没听见什么"
                    current.peak < 300 -> "没听到声音：麦克风被挡住了？"
                    else -> "没认出来：识别服务是不是还没填 key"
                }
            lastError.value = problem
            problem?.let { say(context, it) }
            onDone(result)
        }
    }

    private fun say(context: Context, text: String) {
        runCatching {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

class VoiceNoteSession(private val context: Context) {

    /** 上一次失败的原因、以及这段声音的峰值 —— 界面据此说话。 */
    @Volatile var lastProblem: String? = null
    @Volatile var peak: Int = 0

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
            }.getOrNull() ?: run {
                lastProblem = "麦克风打不开（权限或占用）"
                return false
            }
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
        peak = peakOf(data)
        val text =
            withContext(Dispatchers.IO) {
                runCatching {
                    val speech = SettingsStore(context.applicationContext).load().speech
                    CloudSpeech(speech).transcribe(boost(data, peak))
                }.getOrElse { error ->
                    lastProblem = "识别失败：" + (error.message ?: error.javaClass.simpleName)
                    ""
                }
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

    /** 满量程 32767。对着麦克风正常说话，峰值通常在几千以上。 */
    private fun peakOf(data: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val raw = (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
            val sample = if (raw >= 0x8000) raw - 0x10000 else raw
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    /** 声音太轻，识别服务基本不给字；先把它整体拉起来再送过去。 */
    private fun boost(data: ByteArray, peak: Int): ByteArray {
        if (peak < 300 || peak > 8000) return data
        val factor = (9000.0 / peak).coerceIn(1.0, 16.0)
        val out = ByteArray(data.size)
        var i = 0
        while (i + 1 < data.size) {
            val raw = (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
            val sample = if (raw >= 0x8000) raw - 0x10000 else raw
            val scaled = (sample * factor).toInt().coerceIn(-32768, 32767)
            out[i] = (scaled and 0xff).toByte()
            out[i + 1] = ((scaled shr 8) and 0xff).toByte()
            i += 2
        }
        return out
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
        private const val SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private const val BLOCK = 4096
    }
}
