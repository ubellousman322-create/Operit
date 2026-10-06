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
}
