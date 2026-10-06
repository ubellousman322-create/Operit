package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One reply owns one binary-protocol session/socket. Input is never replayed. */
internal class VolcengineSpeech(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 600_000) {
    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        val first = texts.receiveCatching().getOrNull() ?: return
        try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) { exchange(first, texts, onPcm) }; true
            } ?: throw SpeechApiException("火山语音合成超时。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("火山语音响应无效，请核对模型和音色。")
        }
    }

    internal fun request(): Request = Request.Builder().url(config.volcengineEndpoint(config.ttsBaseUrl)).apply {
        header("X-Api-Resource-Id", config.effectiveVolcResource)
        header("X-Api-Connect-Id", UUID.randomUUID().toString())
        if (config.volcAppId.isNullOrBlank()) header("X-Api-Key", config.ttsKey)
        else { header("X-Api-App-Id", config.volcAppId); header("X-Api-Access-Key", config.ttsKey) }
    }.build()

    internal fun payload(event: Int, text: String? = null): String = JsonObject().apply {
        addProperty("event", event); addProperty("namespace", "BidirectionalTTS")
        add("user", JsonObject().apply { addProperty("uid", "erpan") })
        add("req_params", JsonObject().apply {
            addProperty("speaker", config.voice)
            if (config.effectiveVolcResource == "seed-icl-2.0") addProperty("model", config.ttsModel)
            if (text != null) addProperty("text", text)
            add("audio_params", JsonObject().apply { addProperty("format", "pcm"); addProperty("sample_rate", 24000) })
            if (config.supportsVoicePrompt && !config.voicePrompt.isNullOrBlank()) {
                addProperty("additions", JsonObject().apply {
                    add("context_texts", JsonArray().apply { add(config.voicePrompt) })
                }.toString())
            }
        })
    }.toString()

    private suspend fun exchange(first: String, texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) = coroutineScope {
        val events = Channel<ByteString>(16)
        val listener = object : WebSocketListener() {
            private fun fail(message: String = "火山语音连接中断。") { events.close(SpeechApiException(message)) }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (bytes.size > 4_194_304) { fail("火山语音数据包过大。"); ws.cancel(); return }
                try { runBlocking { events.send(bytes) } } catch (_: Exception) { /* reply canceled */ }
            }
            override fun onMessage(ws: WebSocket, text: String) { fail("火山未返回兼容的二进制语音数据。"); ws.cancel() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code; response?.close()
                fail(if (code != null) "火山语音连接失败（HTTP $code），请检查密钥、资源权限与额度。" else "火山语音网络连接失败。")
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) = fail()
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = fail()
        }
        var socket: WebSocket? = null
        val session = UUID.randomUUID().toString()
        var started = false; var completed = false
        var producer: Job? = null
        try {
            val ws = sockets.newWebSocket(request(), listener).also { socket = it }
            fun send(event: Int, json: String = "{}") {
                if (!ws.send(VolcengineProtocol.encode(event, session, json))) throw SpeechApiException("火山语音发送失败。")
            }
            send(1)
            var connected = false; var total = 0L; var carry: Byte? = null
            val inputFinished = AtomicBoolean(false)
            for (raw in events) {
                ensureActive()
                val frame = VolcengineProtocol.decode(raw)
                if (frame.error != null) throw SpeechApiException("火山合成失败（错误码 ${frame.error}），请核对权限、模型、音色和额度。")
                if (frame.event >= 100) check(frame.session == session)
                when (frame.event) {
                    50 -> { check(!connected); connected = true; send(100, payload(100)) }
                    51, 153 -> throw SpeechApiException("火山合成任务失败，请核对模型、音色和额度。")
                    150 -> {
                        check(connected && !started); started = true
                        producer = launch {
                            var count = 0
                            suspend fun input(text: String) {
                                if (text.isEmpty()) return
                                count += text.length; require(count <= 60_000)
                                while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                                send(200, payload(200, text))
                            }
                            input(first)
                            for (text in texts) { ensureActive(); input(text) }
                            inputFinished.set(true); send(102)
                        }
                    }
                    352 -> {
                        check(started && frame.type == 11)
                        total += frame.payload.size; check(total <= 28_800_000)
                        val aligned = carry?.let { byteArrayOf(it) + frame.payload } ?: frame.payload
                        val size = aligned.size and -2
                        carry = if (size == aligned.size) null else aligned.last()
                        if (size > 0) { ensureActive(); runInterruptible { onPcm(aligned.copyOf(size)) } }
                    }
                    152 -> {
                        check(started && inputFinished.get())
                        if (total == 0L || carry != null) throw SpeechApiException("火山音频为空或 PCM 不完整。")
                        completed = true
                        // SessionFinished already confirms all audio. Connection
                        // cleanup is best effort if the peer has begun closing.
                        ws.send(VolcengineProtocol.encode(2))
                        return@coroutineScope
                    }
                    350, 351, 359 -> check(started) // sentence/subtitle events never end the reply
                    else -> throw SpeechApiException("火山返回不兼容的会话事件。")
                }
            }
            throw SpeechApiException("火山未返回完整的会话结束事件。")
        } finally {
            producer?.cancel()
            if (!completed && started) withContext(NonCancellable) {
                runCatching {
                    socket?.send(VolcengineProtocol.encode(101, session))
                    withTimeoutOrNull(250) {
                        for (raw in events) if (VolcengineProtocol.decode(raw).event == 151) break
                    }
                }
            }
            events.cancel(); socket?.cancel()
        }
    }
}
