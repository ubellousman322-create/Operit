package com.ai.assistance.operit.integrations.mobilevoice

import android.content.Context
import com.ai.assistance.operit.api.speech.SherpaMnnSpeechProvider
import com.huigu.phone10.mobile.LocalAsrHost
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 耳畔的「手机本地识别」落到 Operit 的 Sherpa MNN 上。
 *
 * 不联网，也不抢麦克风：耳畔自己录音、自己管 VAD 和分段，
 * 这里只负责把一块一块 16 kHz 单声道 16 位小端 PCM 当场解码。
 * 录完的时候结果已经在了，不用等。
 */
class OperitLocalAsr(hostContext: Context) : LocalAsrHost {
    private val provider = SherpaMnnSpeechProvider(hostContext.applicationContext)
    // 没开流（比如宿主模型没装）时退化成整段：先攒着，finish 再识别。
    private val buffers = ConcurrentHashMap<Long, ByteArrayOutputStream>()

    override suspend fun start(): Long {
        val id = provider.openPcmStream()
        if (id >= 0) return id
        // 流开不了就不给会话，让调用方直接走整段兵底。
        return -1L
    }

    override fun feed(sessionId: Long, pcm: ByteArray) {
        if (sessionId < 0) return
        provider.feedPcmStream(sessionId, pcm)
    }

    override suspend fun finish(sessionId: Long): String? {
        if (sessionId < 0) return null
        return runCatching { provider.closePcmStream(sessionId) }.getOrNull()
    }

    override fun partial(sessionId: Long): String? =
        if (sessionId < 0) null else runCatching { provider.partialPcmStream(sessionId) }.getOrNull()

    override fun cancel(sessionId: Long) {
        if (sessionId < 0) return
        provider.cancelPcmStream(sessionId)
        buffers.remove(sessionId)
    }

    // 设置页点「检查连接」时把模型加载一遍，好把“模型没下载”这种事提前暴露。
    override suspend fun warmUp(): Boolean = runCatching { provider.initialize() }.getOrDefault(false)
}
