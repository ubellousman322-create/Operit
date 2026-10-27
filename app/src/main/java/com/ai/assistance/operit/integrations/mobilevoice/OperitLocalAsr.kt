package com.ai.assistance.operit.integrations.mobilevoice

import android.content.Context
import com.ai.assistance.operit.api.speech.SherpaMnnSpeechProvider
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.huigu.phone10.mobile.LocalAsrHost

/**
 * 耳畔的「手机本地识别」落到 Operit 的 Sherpa MNN 上。
 *
 * 关键约束：sherpa-mnn 的 native recognizer 进程内只能有一份。自己 new 一个
 * 会和唤醒词那个抢同一块 native 资源，createStream 直接 SIGSEGV。所以这里
 * 一律通过 SpeechServiceFactory 借出，通话结束再还回去。
 */
class OperitLocalAsr(hostContext: Context) : LocalAsrHost {
    private val appContext = hostContext.applicationContext
    private var provider: SherpaMnnSpeechProvider? = null
    private var borrowed = false

    @Synchronized
    private fun ensure(): SherpaMnnSpeechProvider? {
        provider?.let { return it }
        val p = runCatching { SpeechServiceFactory.acquireSherpaMnn(appContext) }.getOrNull()
            ?: return null
        provider = p
        borrowed = true
        return p
    }

    override suspend fun start(): Long {
        val p = ensure() ?: return -1L
        return runCatching { p.openPcmStream() }.getOrDefault(-1L)
    }

    override fun feed(sessionId: Long, pcm: ByteArray) {
        if (sessionId < 0) return
        runCatching { provider?.feedPcmStream(sessionId, pcm) }
    }

    override suspend fun finish(sessionId: Long): String? {
        if (sessionId < 0) return null
        return runCatching { provider?.closePcmStream(sessionId) }.getOrNull()
    }

    override fun partial(sessionId: Long): String? =
        if (sessionId < 0) null else runCatching { provider?.partialPcmStream(sessionId) }.getOrNull()

    override fun cancel(sessionId: Long) {
        if (sessionId < 0) return
        runCatching { provider?.cancelPcmStream(sessionId) }
    }

    override suspend fun warmUp(): Boolean =
        runCatching { ensure()?.initialize() }.getOrNull() == true

    @Synchronized
    override fun shutdown() {
        provider = null
        if (!borrowed) return
        borrowed = false
        runCatching { SpeechServiceFactory.releaseSherpaMnn() }
    }
}
