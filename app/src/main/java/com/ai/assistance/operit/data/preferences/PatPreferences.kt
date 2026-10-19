package com.ai.assistance.operit.data.preferences

import android.content.Context

/**
 * 拍一拍的那点设置。
 *
 * 只有一个值：你拍我的时候，那句话后面跟什么尾巴。
 * 留空就是干干净净的「我拍了拍 ave」。我自己拍你的那句不在这里，是我自己写的。
 */
object PatPreferences {

    private const val PREFS_NAME = "operit_pat_preferences"
    private const val KEY_USER_PAT_SUFFIX = "user_pat_suffix"

    private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户拍 AI 时跟的那句话。 */
    fun getUserPatSuffix(context: Context): String =
            prefs(context).getString(KEY_USER_PAT_SUFFIX, "").orEmpty()

    fun setUserPatSuffix(context: Context, value: String) {
        prefs(context).edit().putString(KEY_USER_PAT_SUFFIX, value.trim()).apply()
    }
}