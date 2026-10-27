package com.huigu.phone10.mobile

import android.content.Context

/**
 * 手机本地识别入口。
 *
 * 宿主（Operit）注册一个实现，耳畔就把录音交给它，不联网。
 * 没注册时耳畔继续走云端，行为不变。
 */
interface LocalAsrHost {
    /** 开一次流式识别会话，返回会话 id；失败返回 -1。 */
    suspend fun start(): Long
    /** 喂入一块 16 kHz、单声道、16 位小端 PCM。会当场解码，不攒到最后。 */
    fun feed(sessionId: Long, pcm: ByteArray)
    /** 收流并取最终文本；没识别出东西返回 null。 */
    suspend fun finish(sessionId: Long): String?
    /** 当前已识别的文本，不结束会话。 */
    fun partial(sessionId: Long): String? = null
    /** 中途放弃。 */
    fun cancel(sessionId: Long)
    /** 预热模型，返回是否可用。设置页检查连接时用。 */
    suspend fun warmUp(): Boolean = true
    /** 通话结束，把借来的识别服务还回去。 */
    fun shutdown() = Unit
}

object LocalAsr {
    @Volatile
    var host: LocalAsrHost? = null

    @Volatile
    var appContext: Context? = null

    /** 先看注册进来的，没注册就按类名自己捉一份。 */
    fun resolve(): LocalAsrHost? {
        host?.let { return it }
        val ctx = appContext ?: return null
        val created =
            runCatching {
                val cls =
                    Class.forName(
                        "com.ai.assistance.operit.integrations.mobilevoice.OperitLocalAsr"
                    )
                cls.getConstructor(Context::class.java).newInstance(ctx) as LocalAsrHost
            }.getOrNull() ?: return null
        host = created
        return created
    }

    fun available(): Boolean = resolve() != null
}
