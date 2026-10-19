package com.ai.assistance.operit.data.preferences

import android.content.Context

/**
 * 记一下哪些红包/转账已经被点开收下了。
 *
 * 键用的是那条消息的时间戳，全局唯一，不用动数据库表结构。
 * 收下之后卡片会变灰、写"已接收"，退出去再回来还是这个样子。
 */
object PaymentReceiptPreferences {

    private const val PREFS_NAME = "operit_payment_receipts"
    private const val KEY_RECEIVED = "received_timestamps"

    private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isReceived(context: Context, messageTimestamp: Long): Boolean {
        if (messageTimestamp <= 0L) return false
        return prefs(context)
                .getStringSet(KEY_RECEIVED, emptySet())
                .orEmpty()
                .contains(messageTimestamp.toString())
    }

    fun markReceived(context: Context, messageTimestamp: Long) {
        if (messageTimestamp <= 0L) return
        val current = prefs(context).getStringSet(KEY_RECEIVED, emptySet()).orEmpty().toMutableSet()
        if (current.add(messageTimestamp.toString())) {
            prefs(context).edit().putStringSet(KEY_RECEIVED, current).apply()
        }
    }
}