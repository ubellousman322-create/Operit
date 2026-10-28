package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Official Flash TTS socket: one reply, one owner, no replay after failure. */
internal class ElevenLabsSpeech(private val config: SpeechConfig, private val sockets: WebSocket.Factory,
    private val timeoutMillis: Long = 1_800_000, private val heartbeatMillis: Long = 15_000) {
    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        val first = texts.receiveCatching().getOrNull() ?: return
        try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) { exchange(first, texts, onPcm) }; true
            } ?: throw SpeechApiException("ElevenLabs 合成超时，本轮已停止。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw if (error is SpeechApiException) error
                else SpeechApiException("ElevenLabs 音频或结束事件无效（${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}）。")
        }
    }

    private suspend fun exchange(first: String, texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) = coroutineScope {
        val events = Channel<String>(8)
        val listener = object : WebSocketListener() {
            fun fail(message: String = "ElevenLabs 连接提前中断，本轮不会自动重播。") {
                events.close(SpeechApiException(message))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 4_194_304) { fail(); webSocket.cancel(); return }
                try { runBlocking { events.send(text) } } catch (_: Exception) { /* cancelled receiver */ }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(); webSocket.cancel() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code; response?.close()
                android.util.Log.d("VoiceDiag", "eleven_fail status=$status err=${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
                fail(when (status) {
                    401, 403 -> "ElevenLabs 密钥无效或没有合成权限，请检查账号和 API Key。"
                    402, 429 -> "ElevenLabs 额度不足或请求受限，请查看平台额度。"
                    else -> "ElevenLabs 连接失败，请检查网络与账号；本轮不会自动重播。"
                })
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d("VoiceDiag", "eleven_closing code=$code")
                // 说完最后一块音频后服务端会主动收线，这不算错误。
                if (code == 1000 || code == 1001 || code == 1005) events.close()
                else fail("ElevenLabs 连接被中断（$code），本轮不会自动重播。")
                runCatching { webSocket.close(1000, null) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d("VoiceDiag", "eleven_closed code=$code")
                events.close()
            }
        }
        var socket: WebSocket? = null
        var input: Job? = null
        try {
            val target = config.elevenEndpoint().toString().replaceFirst("https://", "wss://")
            android.util.Log.d("VoiceDiag", "eleven_open url=$target")
            val ws = sockets.newWebSocket(Request.Builder().url(target)
                .header("xi-api-key", config.ttsKey).build(), listener).also { socket = it }
            fun send(text: String) {
                val json = JsonObject().apply { addProperty("text", text) }
                if (!ws.send(json.toString())) throw SpeechApiException("ElevenLabs 文字发送失败，本轮不会自动重发。")
            }
            send(" ")
            val inputFinished = AtomicBoolean(false)
            input = launch {
                var characters = 0
                suspend fun append(text: String) {
                    if (text.isBlank()) return
                    characters += text.codePointCount(0, text.length)
                    require(characters <= 40_000) { "ElevenLabs 单条回复过长。" }
                    var begin = 0
                    while (begin < text.length) {
                        var end = minOf(begin + 4000, text.length)
                        if (end < text.length && text[end - 1].isHighSurrogate()) end--
                        while (ws.queueSize() > 262_144) { delay(10); ensureActive() }
                        val part = text.substring(begin, end)
                        send(if (part.endsWith(' ')) part else "$part ")
                        begin = end
                    }
                }
                append(first)
                while (true) {
                    // A tool pause is not end of input. Only this producer writes to the socket.
                    val next = withTimeoutOrNull(heartbeatMillis) { texts.receiveCatching() }
                    if (next == null) { send(" "); continue }
                    next.exceptionOrNull()?.let { throw it }
                    val text = next.getOrNull() ?: break
                    append(text)
                }
                inputFinished.set(true)
                android.util.Log.d("VoiceDiag", "eleven_input_done chars=$characters")
                send("")
            }
            var carry: Byte? = null
            var total = 0L
            var sawFinal = false
            while (true) {
                // 收到 isFinal 只代表“这一段结束”，不是全部结束；
                // 之后还要把后面的音频收完，所以给它一个 10 秒的宽限窗口。
                val next = if (sawFinal) {
                    withTimeoutOrNull(10_000) { events.receiveCatching() } ?: break
                } else events.receiveCatching()
                if (next.isClosed) break
                val raw = next.getOrNull() ?: break
                ensureActive()
                val json = JsonParser.parseString(raw).asJsonObject
                android.util.Log.d("VoiceDiag", "eleven_msg keys=${json.keySet().joinToString(",")} len=${raw.length}")
                if (json.has("error") || json.has("message")) throw SpeechApiException("ElevenLabs 合成失败，请检查模型、音色、密钥与额度。")
                val audio = json["audio"]?.takeUnless { it.isJsonNull }?.asString
                if (!audio.isNullOrEmpty()) {
                    val bytes = Base64.getDecoder().decode(audio)
                    total += bytes.size; require(total <= 86_400_000)
                    val aligned = carry?.let { byteArrayOf(it) + bytes } ?: bytes
                    val even = aligned.size and -2
                    carry = if (even != aligned.size) aligned.last() else null
                    if (even > 0) runInterruptible { onPcm(aligned.copyOf(even)) }
                }
                if (json["isFinal"]?.asBoolean == true) {
                    android.util.Log.d("VoiceDiag", "eleven_final inputFinished=${inputFinished.get()} total=$total carry=${carry != null}")
                    sawFinal = true
                }
            }
            // 最后一块是奇数个字节时只丢掉半个采样，绝不能因此把整段播放判死。
            if (total <= 0) throw SpeechApiException("ElevenLabs 没有返回音频。")
            if (!sawFinal) throw SpeechApiException("ElevenLabs 缺少结束事件。")
            android.util.Log.d("VoiceDiag", "eleven_done total=$total carry=${carry != null}")
            return@coroutineScope
        } finally { input?.cancel(); events.cancel(); socket?.cancel() }
    }
}
