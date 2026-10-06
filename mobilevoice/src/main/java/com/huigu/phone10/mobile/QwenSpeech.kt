package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/** Retry only rejected handshakes. An accepted synthesis is never replayed. */
internal class QwenSpeech(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 1_800_000,
    private val onStatus: suspend (String) -> Unit = {},
    private val lifecycle: QwenLifecycle? = null) {
    private class UnavailableBeforeOpen : Exception()
    private class BusyBeforeOpen : Exception()
    private sealed interface Frame {
        data object Open : Frame
        data class Text(val value: String) : Frame
        data class Pcm(val value: ByteArray) : Frame
    }

    suspend fun speakStream(texts: ReceiveChannel<String>,
        onPlayed: suspend () -> Unit = {}, onAbort: () -> Unit = {}, onSegment: (String) -> Unit = {}, onPcm: (ByteArray) -> Unit) {
        if (lifecycle != null) lifecycle.withSession { speakOwned(texts,onPlayed,onAbort,onSegment,onPcm) }
        else speakOwned(texts,onPlayed,onAbort,onSegment,onPcm)
    }

    private suspend fun speakOwned(texts: ReceiveChannel<String>,
        onPlayed: suspend () -> Unit, onAbort: () -> Unit, onSegment: (String) -> Unit, onPcm: (ByteArray) -> Unit) {
        var first: String
        while (true) {
            first = texts.receiveCatching().getOrThrowOrNull() ?: return
            if (first.isNotBlank()) break
        }
        VoiceDiagnostics.record("qwen_first_fragment")
        var lease: String? = null
        var played = false
        try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    lease = lifecycle?.activateAndWait(onStatus)
                    if (config.clientSegmentedTts) segmentedExchange(first, texts, onSegment, onPcm)
                    else exchangeWithRetry(first, texts, onPcm)
                    onPlayed()
                    played = true
                }
                true
            } ?: throw SpeechApiException("本机语音 合成超时，已取消本轮请求。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error else SpeechApiException("本机语音 音频或结束事件不完整，请检查电脑端语音状态。")
        } finally {
            try { if (!played) onAbort() }
            finally { lease?.let { id -> withContext(NonCancellable) {
                if (played) lifecycle?.release(id) else lifecycle?.cancelAndRelease(id,onStatus)
            } } }
        }
    }

    private suspend fun segmentedExchange(first: String, texts: ReceiveChannel<String>,
        onSegment: (String) -> Unit, onPcm: (ByteArray) -> Unit) = coroutineScope {
        val segments = Channel<String>(Channel.RENDEZVOUS)
        val feeder = launch {
            try { segments.send(first); for (text in texts) if (text.isNotBlank()) segments.send(text) }
            finally { segments.close() }
        }
        try {
            SentenceSpeech { text, rate, pcm ->
                // This mode receives completed client segments, never raw tokens.
                // Bound accidental direct callers as well as the UI segmenter.
                if (text.codePointCount(0,text.length) > if (config.ttsModel == "indextts2") 600 else 2000)
                    throw SpeechApiException("本机语音单段过长，请分段后重试。")
                val end = Channel<String>(1).apply { close() }
                rate(24_000)
                lifecycle?.awaitAvailable(onStatus)
                exchangeWithRetry(text, end, pcm, exactSegment = true)
            }.speak(segments, { check(it == 24_000) }, onSegment) { pcm -> runInterruptible { onPcm(pcm) } }
        } finally { feeder.cancel(); segments.cancel() }
    }

    private suspend fun exchangeWithRetry(first: String, texts: ReceiveChannel<String>,
        onPcm: (ByteArray) -> Unit, exactSegment: Boolean = false) {
        var retries = 0
        while (true) {
            try { exchange(first, texts, onPcm, exactSegment); return }
            catch (_: UnavailableBeforeOpen) {
                if (retries == 2) throw SpeechApiException("本机语音 服务暂不可用，连接重试已结束；本条未合成，请稍后再试。")
                retries++
                VoiceDiagnostics.record("qwen_connect_retry_503_$retries")
                onStatus("语音服务暂不可用 · 等待重连（$retries/2）…")
                delay(retries * 2000L)
            }
            catch (_: BusyBeforeOpen) {
                if (retries == 2) throw SpeechApiException("本机语音 服务持续忙碌，本段未合成，请稍后再试。")
                retries++
                onStatus("本机语音正在准备 · 等待上段清理…")
                delay(250)
                lifecycle?.awaitAvailable(onStatus)
            }
        }
    }

    private suspend fun exchange(first: String, texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit,
        exactSegment: Boolean = false) = coroutineScope {
        val events = Channel<Frame>(16)
        val finishedInput = AtomicBoolean(false)
        val receivedPcm = AtomicBoolean(false)
        val socketOpened = AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            private fun deliver(frame: Frame) {
                try { runBlocking { events.send(frame) } }
                catch (_: Exception) {
                    // A closed Channel throws its original transport cause, not necessarily
                    // ClosedSendChannelException. Its owner reports it through the coroutine.
                }
            }
            private fun fail(message: String) { events.close(SpeechApiException(message)) }
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketOpened.set(true)
                VoiceDiagnostics.record("qwen_socket_open")
                deliver(Frame.Open)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 65_536) { fail("本机语音 返回的控制消息过大。"); webSocket.cancel() }
                else deliver(Frame.Text(text))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 2_097_152) { fail("本机语音 返回的音频块过大。"); webSocket.cancel() }
                else {
                    if (receivedPcm.compareAndSet(false, true)) {
                        VoiceDiagnostics.record("qwen_first_pcm_received")
                        VoiceDiagnostics.record("qwen_first_pcm_bytes_${bytes.size}")
                    }
                    deliver(Frame.Pcm(bytes.toByteArray()))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                VoiceDiagnostics.record("qwen_connect_failed_http_${code ?: 0}")
                if (code == 429 && !socketOpened.get() && config.ttsModel == "indextts2" && lifecycle != null) {
                    response.close()
                    events.close(BusyBeforeOpen())
                    return
                }
                if (code == 503 && !socketOpened.get()) {
                    response.close()
                    events.close(UnavailableBeforeOpen())
                    return
                }
                val message = when (response?.code) {
                    401, 403 -> "本机语音 令牌无效或无访问权限。"
                    429 -> "本机语音 正被其他连接占用，请稍后再试。"
                    503 -> "本机语音 尚未预热完成或服务不可用，请查看电脑端状态。"
                    else -> "本机语音 连接中断，请检查电脑语音服务和 Tailscale 连接。"
                }
                response?.close(); fail(message)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = fail("本机语音 提前关闭连接，语音未完整返回。")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail("本机语音 连接已关闭，语音未完整返回。")
        }
        var socket: WebSocket? = null
        try {
            VoiceDiagnostics.record("qwen_connect_begin")
            val ws = sockets.newWebSocket(Request.Builder().url(config.qwenEndpoint(config.ttsBaseUrl))
                .header("Authorization", "Bearer ${config.ttsKey}").build(), listener).also { socket = it }
            fun send(type: String, fields: JsonObject.() -> Unit = {}) {
                val message = JsonObject().apply { addProperty("type", type); fields() }
                if (!ws.send(message.toString())) throw SpeechApiException("本机语音 文字发送失败，本轮不会自动重发。")
            }
            val session = QwenPcmSession()
            var opened = false
            for (frame in events) {
                ensureActive()
                when (frame) {
                    Frame.Open -> {
                        check(!opened); opened = true
                        onStatus("语音服务已连接 · 等待音频…")
                        send("session.config") {
                            addProperty("model", config.ttsModel); addProperty("voice", config.voice)
                            addProperty("split_granularity", if (exactSegment) "none" else config.effectiveQwenSplitGranularity)
                            addProperty("stream_audio", true)
                            addProperty("response_format", "pcm")
                        }
                        launch {
                            val input = QwenTextInput()
                            var sent = false
                            suspend fun pieces(values: List<String>) {
                                for (value in values) {
                                    ensureActive()
                                    if (!sent) VoiceDiagnostics.record("qwen_first_sentence_ready")
                                    while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                                    send("input.text") { addProperty("text", value) }
                                    if (!sent) VoiceDiagnostics.record("qwen_first_text_queued")
                                    sent = true
                                }
                            }
                            if (config.ttsModel == "indextts2") {
                                // Index consumes a complete bounded request. Preserve
                                // text exactly; do not inject Qwen sentence separators.
                                val body = StringBuilder(first)
                                if (!exactSegment) for (part in texts) {
                                    body.append(part)
                                    if (body.length > 1201) throw SpeechApiException("Index 单段最多 600 个字符，请使用首句先读或缩短文字。")
                                }
                                val value = body.toString()
                                if (value.codePointCount(0,value.length) > 600)
                                    throw SpeechApiException("Index 单段最多 600 个字符，请使用首句先读或缩短文字。")
                                require(value.codePoints().noneMatch { it in 0xD800..0xDFFF })
                                pieces(listOf(value))
                            } else if (exactSegment) {
                                // No synthetic newline: every request preserves its
                                // already-cleaned segment, including punctuation.
                                pieces(listOf(first))
                            } else {
                                pieces(input.push(first))
                                for (text in texts) pieces(input.push(text))
                                pieces(input.finish())
                            }
                            check(sent)
                            finishedInput.set(true)
                            send("input.done")
                            VoiceDiagnostics.record("qwen_input_done_queued")
                        }
                    }
                    is Frame.Text -> if (session.event(frame.value, finishedInput.get())) return@coroutineScope
                    is Frame.Pcm -> {
                        session.pcm(frame.value)
                        runInterruptible { onPcm(frame.value) }
                    }
                }
            }
            throw SpeechApiException("本机语音 缺少完整的结束事件。")
        } finally { events.cancel(); socket?.cancel() }
    }

    private fun <T> kotlinx.coroutines.channels.ChannelResult<T>.getOrThrowOrNull(): T? {
        exceptionOrNull()?.let { throw it }
        return getOrNull()
    }
}
