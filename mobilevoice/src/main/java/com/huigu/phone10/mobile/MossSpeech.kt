package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Response
import java.io.EOFException
import java.util.Base64

/** Moss HTTP text-in/audio-out protocol. Text continuation is not advertised by this endpoint. */
internal object MossSpeech {
    fun request(config: SpeechConfig, text: String) = JsonObject().apply {
        addProperty("model", config.ttsModel)
        addProperty("input", text)
        addProperty("voice_id", config.voice)
        addProperty("speed", config.effectiveMossSpeed)
        addProperty("stream", true)
        addProperty("response_format", "pcm")
        // SSE declares the audio format and explicit completion; raw PCM EOF does not.
        addProperty("stream_format", "sse")
    }

    fun consume(response: Response, active: () -> Boolean, onSampleRate: (Int) -> Unit, onPcm: (ByteArray) -> Unit) {
        if (response.body.contentType()?.let { "${it.type}/${it.subtype}" } != "text/event-stream")
            throw SpeechApiException("Mossland 未返回流式语音，请核对模型与接口地址。")
        val source = response.body.source()
        val event = StringBuilder()
        var started = false
        var carry: Byte? = null
        var bytes = 0L

        fun dispatch(): Boolean {
            if (event.isEmpty()) return false
            val json = JsonParser.parseString(event.toString()).asJsonObject
            event.setLength(0)
            when (json["type"]?.asString) {
                "task.created" -> Unit
                "speech.created" -> {
                    if (started || json["format"]?.asString != "pcm" ||
                        json["channels"]?.asInt != 1 || json["bit_depth"]?.asInt != 16)
                        throw SpeechApiException("Mossland 返回了不支持的音频格式。")
                    val rate = json["sample_rate"]?.asInt ?: 0
                    if (rate !in 8000..48000) throw SpeechApiException("Mossland 返回了不支持的采样率。")
                    if (active()) onSampleRate(rate)
                    started = true
                }
                "speech.audio.delta" -> {
                    if (!started) throw SpeechApiException("Mossland 缺少音频格式信息。")
                    val encoded = json["audio"]?.asString.orEmpty()
                    if (encoded.isNotEmpty()) {
                        val chunk = Base64.getDecoder().decode(encoded)
                        val pcm = carry?.let { byteArrayOf(it) + chunk } ?: chunk
                        val count = pcm.size and -2
                        carry = if (count < pcm.size) pcm.last() else null
                        if (count > 0 && active()) {
                            if (bytes == 0L) VoiceDiagnostics.record("moss_first_pcm")
                            onPcm(pcm.copyOf(count))
                            bytes += count
                        }
                    }
                }
                "speech.audio.done" -> {
                    if (!started || carry != null || bytes == 0L)
                        throw SpeechApiException("Mossland 音频为空或不完整。")
                    VoiceDiagnostics.record("moss_done_bytes_$bytes")
                    return true
                }
                "error" -> throw SpeechApiException("Mossland 合成失败，请核对密钥、音色与额度。")
                else -> throw SpeechApiException("Mossland 返回了不支持的语音事件。")
            }
            return false
        }

        while (active()) {
            val line = try { source.readUtf8LineStrict(MAX_EVENT_BYTES.toLong()) }
            catch (_: EOFException) {
                throw SpeechApiException("Mossland 流提前结束或事件过大，请重试本轮语音。")
            }
            if (!active()) return
            if (line.isEmpty()) {
                if (dispatch()) return
            } else if (line.startsWith("data:")) {
                val data = line.substring(5).removePrefix(" ")
                if (event.length + data.length + 1 > MAX_EVENT_BYTES)
                    throw SpeechApiException("Mossland 语音事件过大。")
                if (event.isNotEmpty()) event.append('\n')
                event.append(data)
            }
            // SSE comments/heartbeats and event/id fields carry no audio.
        }
    }

    private const val MAX_EVENT_BYTES = 1_048_576
}
