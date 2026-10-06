package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The gateway keeps its model awake until this phone acknowledges playback, not just audio.done. */
internal class QwenLifecycle(private val config: SpeechConfig, private val http: OkHttpClient,
    baseForTest: HttpUrl? = null, private val pollMillis: Long = 2_000,
    private val readyTimeoutMillis: Long = 360_000, private val cleanupTimeoutMillis: Long = 15_000) {
    private val base = baseForTest ?: URI(config.qwenEndpoint(config.ttsBaseUrl)).let { uri ->
        HttpUrl.Builder().scheme("https").host(uri.host)
            .apply { if (uri.port != -1) port(uri.port) }.build()
    }
    private val index = config.ttsModel == "indextts2"
    // Shared across call instances: an unresolved cancellation must survive ending
    // and restarting a call within this app process. Never log this private key.
    private val session = sessions.computeIfAbsent(base.toString() + config.ttsKey) { Session() }
    private class Session { val mutex = Mutex(); var pendingLease: String? = null }
    companion object { private val sessions = ConcurrentHashMap<String, Session>() }

    suspend fun voices(): List<LocalVoice> = try {
        withTimeout(15_000) {
            val (status, data) = response(request("/v1/audio/voices", authorized = true))
            if (status != 200) throw failure(status, data)
            LocalVoice.parseCatalog(data.toString())
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: SpeechApiException) { throw error }
    catch (_: Exception) { throw SpeechApiException("无法刷新本机声音列表，请检查电脑语音服务和连接；已保存方案保持不变。") }

    suspend fun <T> withSession(block: suspend () -> T): T =
        if (index) session.mutex.withLock { block() } else block()

    private fun idle(health: JsonObject) = !index ||
        (health.get("busy")?.asBoolean == false && health.get("restart_required")?.asBoolean == false)

    suspend fun awaitAvailable(onStatus: suspend (String) -> Unit, requireVoice: Boolean = true) {
        if (!index) return
        val ready = withTimeoutOrNull(cleanupTimeoutMillis) {
            var reported = false
            while (true) {
                val (status, data) = response(request("/health"))
                if (data.get("restart_required")?.asBoolean == true)
                    throw SpeechApiException("本机语音 需要电脑端恢复后才能继续。")
                val state = data.get("state")?.asString
                if (state == "off") throw SpeechApiException("5090 的语音已手动关闭，需要在电脑端开启后再试。")
                if (state == "failed") throw SpeechApiException("本机语音 服务失败，请查看电脑端状态。")
                if (status == 200 && state == "ready" && idle(data) &&
                    data.get("low_latency_ready")?.asBoolean == true && (!requireVoice || voiceReady(data))) break
                if (status !in setOf(200,503,429)) throw failure(status,data)
                if (!reported) { onStatus("本机语音正在停止或准备 · 等待服务空闲…"); reported = true }
                delay(minOf(pollMillis,250))
            }
            true
        } ?: false
        if (!ready) throw SpeechApiException("本机语音 等待服务空闲超时，本段未重新发送。")
    }

    suspend fun cancelAndRelease(lease: String, onStatus: suspend (String) -> Unit) {
        if (!index) { release(lease); return }
        session.pendingLease = lease
        try {
            onStatus("本机语音正在停止 · 等待电脑确认…")
            awaitAvailable(onStatus, requireVoice = false)
            release(lease)
            if (session.pendingLease != null) throw SpeechApiException("租约尚未释放")
            onStatus("本机语音已停止")
        } catch (_: Exception) {
            VoiceDiagnostics.record("qwen_cancel_unconfirmed")
            onStatus("本机语音取消尚未确认 · 下次使用前将重新核对")
        }
    }
    private fun url(path: String) = base.newBuilder().encodedPath(path).build()
    private fun request(path: String, body: JsonObject? = null, authorized: Boolean = false): Request =
        Request.Builder().url(url(path)).apply {
            if (authorized) header("Authorization", "Bearer ${config.ttsKey}")
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()

    private suspend fun response(request: Request): Pair<Int, JsonObject> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val bytes = it.body?.source()?.let { source ->
                            source.request(65_537)
                            source.readByteArray(minOf(source.buffer.size, 65_537L))
                        } ?: byteArrayOf()
                        if (bytes.size > 65_536) throw SpeechApiException("本机语音 状态响应过大。")
                        val parsed = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                        if (!parsed.isJsonObject) throw SpeechApiException("本机语音 状态响应无效。")
                        if (continuation.isActive) continuation.resume(it.code to parsed.asJsonObject)
                    }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    private fun code(json: JsonObject): String? = json.getAsJsonObject("error")?.get("code")?.asString
    private fun failure(status: Int, json: JsonObject): SpeechApiException = when (status) {
        401, 403 -> SpeechApiException("本机语音 令牌无效或无访问权限。")
        423 -> SpeechApiException("5090 的语音已手动关闭，需要在电脑端开启后再试。")
        409 -> when (code(json)) {
            "PLAYBACK_ACK_REQUIRED" -> SpeechApiException("语音服务仍在等待旧播放结束，请先停止旧播放再试。")
            else -> SpeechApiException("本机语音 状态冲突，请稍后再试。")
        }
        else -> SpeechApiException("本机语音 启动失败，请查看电脑端语音状态。")
    }

    suspend fun activateAndWait(onStatus: suspend (String) -> Unit): String {
        if (index) session.pendingLease?.let { old ->
            onStatus("本机语音取消尚未确认 · 正在核对旧请求…")
            awaitAvailable(onStatus, requireVoice = false)
            release(old)
            if (session.pendingLease != null)
                throw SpeechApiException("本机语音 旧播放租约尚未释放，请稍后再试。")
        }
        val (status, data) = response(request("/v1/audio/activate", JsonObject(), authorized = true))
        if (status !in setOf(200, 202)) throw failure(status, data)
        val lease = data.get("lease_id")?.asString?.takeIf { it.length in 8..256 }
            ?: throw SpeechApiException("本机语音 未返回播放租约。")
        VoiceDiagnostics.record("qwen_lease_acquired")
        if (index) session.pendingLease = lease
        try {
            onStatus("语音服务准备中 · 等待音色就绪…")
            val ready = withTimeoutOrNull(readyTimeoutMillis) {
                while (true) {
                    val (healthCode, health) = response(request("/health"))
                    val state = health.get("state")?.asString
                    if (state == "failed") throw SpeechApiException("本机语音 启动失败，请查看电脑端状态。")
                    if (state == "off") throw SpeechApiException("5090 的语音已手动关闭，需要在电脑端开启后再试。")
                    if (index && health.get("restart_required")?.asBoolean == true)
                        throw SpeechApiException("本机语音 需要电脑端恢复后才能继续。")
                    if (healthCode == 200 && state == "ready" &&
                        health.get("low_latency_ready")?.asBoolean == true && voiceReady(health) && idle(health)) {
                        VoiceDiagnostics.record("qwen_voice_ready")
                        break
                    }
                    if (healthCode != 200 && healthCode != 503) throw failure(healthCode, health)
                    delay(pollMillis)
                }
                true
            } ?: false
            if (!ready) throw SpeechApiException("本机语音 准备超时，本条未合成。")
            return lease
        } catch (error: Throwable) {
            withContext(NonCancellable) { release(lease) }
            throw error
        }
    }

    private fun voiceReady(health: JsonObject): Boolean {
        val voices = health.getAsJsonObject("named_voices")
        val voice = voices?.getAsJsonObject(config.voice)
        if (voice == null) {
            if (index) throw SpeechApiException("电脑端没有这个 Index 音色，请刷新声音列表后重新选择。")
            return config.voice == "xiaoying-a"
        }
        return voice.get("ready")?.asBoolean == true
    }

    suspend fun release(lease: String) {
        try {
            withTimeout(10_000) {
                val data = JsonObject().apply { addProperty("lease_id", lease) }
                val (status, json) = response(request("/v1/audio/release", data, authorized = true))
                if (status == 200 && json.get("released")?.asBoolean == true) {
                    if (index && session.pendingLease == lease) session.pendingLease = null
                    VoiceDiagnostics.record("qwen_lease_released")
                } else VoiceDiagnostics.record("qwen_lease_release_http_$status")
            }
        } catch (_: Exception) { VoiceDiagnostics.record("qwen_lease_release_failed") }
    }
}
