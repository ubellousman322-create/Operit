package com.ai.assistance.operit.integrations.mobilevoice

import android.content.Context
import com.ai.assistance.operit.api.speech.SherpaMnnSpeechProvider
import com.huigu.phone10.mobile.LocalAsrHost
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 耳畔的「手机本地识别」落在 Operit 的 Sherpa MNN 上。
 *
 * 不联网，也不抢麦克风：耳畔自己录音、自己做 VAD 和分段，
 * 这里只把一段 16 kHz 单声道 16 位小端 PCM 变成文字。
 */
class OperitLocalAsr(hostContext: Context) : LocalAsrHost {
    private val provider = SherpaMnnSpeechProvider(hostContext.applicationContext)
    private val buffers = ConcurrentHashMap<Long, ByteArrayOutputStream>()
    private val ids = AtomicLong(0L)

    override fun start(): Long {
        val id = ids.incrementAndGet()
        buffers[id] = ByteArrayOutputStream()
        return id
    }

    override fun feed(sessionId: Long, pcm: ByteArray) {
        val buffer = buffers[sessionId] ?: return
        synchronized(buffer) { buffer.write(pcm) }
    }

    override suspend fun finish(sessionId: Long): String? {
        val buffer = buffers.remove(sessionId) ?: return null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        // 少于 0.2 秒的碎音直接丢，模型也识别不出东西。
        if (pcm.size < 6400) return null
        return runCatching { provider.recognizePcm(pcm) }.getOrNull()
    }

    override fun cancel(sessionId: Long) {
        buffers.remove(sessionId)
    }

    // 设置页点「检查连接」时把模型加载一遍，好把“模型没下载”这种事提前暴露。
    override suspend fun warmUp(): Boolean = runCatching { provider.initialize() }.getOrDefault(false)
}
