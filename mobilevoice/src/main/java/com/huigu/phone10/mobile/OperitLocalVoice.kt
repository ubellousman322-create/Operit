package com.huigu.phone10.mobile

import android.content.Context

/**
 * Operit 内置直连入口。
 *
 * 耳畔原本把每个请求交给 Operit 的插件沙箱走一圈
 * （广播 -> 插件 -> JS -> Chat API，三跳）。内置之后由宿主 APK 注册一个直连实现，
 * [OperitBridge] 优先走它；没有注册时退回原来的广播路径，行为不变。
 */
interface OperitLocalVoiceHost {
    fun dispatch(context: Context, pending: OperitPending)
    /** 取消一个还在飞的请求；内置实现里直接把它标掉即可。 */
    fun cancel(context: Context, target: OperitPending)

    /** 通话页正中那张脸。宿主拿得到就用宿主的，拿不到返回 null。 */
    fun callAvatar(): android.graphics.drawable.Drawable?
}

object OperitLocalVoice {
    @Volatile
    var host: OperitLocalVoiceHost? = null

    /** 宿主进程的 Application context。只存一个引用，任何时候设都不会失败。 */
    @Volatile
    var appContext: Context? = null

    /**
     * 拿不到注册进来的实现时，照着类名自己捞一份。
     * 宿主注册的时机一旦没踩对（进程是别的入口先拉起来的），
     * 这里就是最后一道，不至于让整条直连静默变哑。
     */
    fun resolve(): OperitLocalVoiceHost? {
        host?.let { return it }
        val ctx = appContext ?: return null
        val created =
            runCatching {
                val cls = Class.forName("com.ai.assistance.operit.integrations.mobilevoice.OperitMobileVoiceHost")
                cls.getConstructor(Context::class.java).newInstance(ctx) as OperitLocalVoiceHost
            }.getOrNull() ?: return null
        host = created
        return created
    }
}
