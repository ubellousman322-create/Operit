package com.ai.assistance.operit.integrations.mobilevoice

import android.content.Context
import com.ai.assistance.operit.api.speech.SherpaSpeechProvider
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import com.huigu.phone10.mobile.LocalAsrHost

/**
 * 耳畔的「手机本地识别」落到 Operit 的 Sherpa NCNN 上。
 *
 * 用 NCNN 不用 MNN：NCNN 的模型已经在手机上（.sherpa_ncnn_models），
 * MNN 那套模型从来没随包发过，建 recognizer 时 native 句柄是 0，
 * createStream 会直接 SIGSEGV。
 *
 * sherpa-ncnn 没有独立流对象，状态全在 recognizer 里，所以依赖工厂的
 * 单实例 + 引用计数，保证同一时刻只有一方在喂音频。
 */
class OperitLocalAsr(hostContext: Context) : LocalAsrHost {
    private val appContext = hostContext.applicationContext
    private var provider: SherpaSpeechProvider? = null
    private var borrowed = false

    // NCNN 只有一个流，会话号固定，只是拿来对齐接口。
    private val session = 1L

    @Synchronized
    private fun ensure(): SherpaSpeechProvider? {
        provider?.let { return it }
        val p = runCatching { SpeechServiceFactory.acquireSherpaNcnn(appContext) }.getOrNull()
            ?: return null
        provider = p
        borrowed = true
        return p
    }

    override suspend fun start(): Long {
        val p = ensure() ?: return -1L
        return if (runCatching { p.openVoiceStream() }.getOrDefault(false)) session else -1L
    }

    override fun feed(sessionId: Long, pcm: ByteArray) {
        if (sessionId < 0) return
        runCatching { provider?.feedVoiceStream(pcm) }
    }

    override suspend fun finish(sessionId: Long): String? {
        if (sessionId < 0) return null
        return runCatching { provider?.closeVoiceStream() }.getOrNull()
    }

    override fun partial(sessionId: Long): String? =
        if (sessionId < 0) null else runCatching { provider?.recognitionResultFlow?.value?.text }.getOrNull()

    override fun cancel(sessionId: Long) {
        if (sessionId < 0) return
        runCatching { provider?.cancelVoiceStream() }
    }

    override suspend fun warmUp(): Boolean =
        runCatching { ensure()?.initialize() }.getOrNull() == true

    @Synchronized
    override fun shutdown() {
        provider = null
        if (!borrowed) return
        borrowed = false
        runCatching { SpeechServiceFactory.releaseSherpaNcnn() }
    }
}
