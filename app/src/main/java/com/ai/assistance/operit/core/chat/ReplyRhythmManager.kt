package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.data.preferences.ReplyRhythmPreferences
import com.ai.assistance.operit.data.preferences.ReplyWindow
import com.ai.assistance.operit.data.preferences.ReplyWindowRepeat
import com.ai.assistance.operit.data.preferences.ReplyWindowType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 当前回复节奏状态 */
data class ReplyRhythmStatus(
        /** 是否应该攒着不回复 */
        val silent: Boolean,
        /** 静默是否来自手动「现在开始攒着」 */
        val manual: Boolean,
        /** 当前是否落在「不静默」窗口内 */
        val activeOverride: Boolean,
        /** 预计恢复回复的时间戳；null 表示需要手动解除 */
        val releaseAtMillis: Long?,
        /** 命中的窗口备注 */
        val label: String
)

object ReplyRhythmManager {

    fun minuteOfDay(now: LocalDateTime): Int = now.hour * 60 + now.minute

    fun dateMatches(window: ReplyWindow, date: LocalDate): Boolean =
            when (window.repeat) {
                ReplyWindowRepeat.EVERY_DAY -> true
                ReplyWindowRepeat.WORKDAYS -> date.dayOfWeek.value in 1..5
                ReplyWindowRepeat.WEEKEND -> date.dayOfWeek.value >= 6
                ReplyWindowRepeat.CUSTOM_WEEKDAYS -> date.dayOfWeek.value in window.weekdays
                ReplyWindowRepeat.ONCE -> window.date == date.toString()
            }

    fun windowMatches(window: ReplyWindow, now: LocalDateTime): Boolean {
        val minute = minuteOfDay(now)
        if (!window.crossesMidnight) {
            return dateMatches(window, now.toLocalDate()) &&
                    minute >= window.startMinute &&
                    minute < window.endMinute
        }
        if (dateMatches(window, now.toLocalDate()) && minute >= window.startMinute) return true
        return dateMatches(window, now.toLocalDate().minusDays(1)) && minute < window.endMinute
    }

    fun isSilentAt(
            windows: List<ReplyWindow>,
            defaultSilent: Boolean,
            now: LocalDateTime
    ): Boolean {
        if (windows.any { it.type == ReplyWindowType.ACTIVE && windowMatches(it, now) }) return false
        if (windows.any { it.type == ReplyWindowType.SILENT && windowMatches(it, now) }) return true
        return defaultSilent
    }

    /** 从 now 起向后扫描，找出第一个不再静默的时刻（一分钟粒度） */
    fun nextRelease(
            windows: List<ReplyWindow>,
            defaultSilent: Boolean,
            now: LocalDateTime
    ): LocalDateTime? {
        var cursor = now.withSecond(0).withNano(0).plusMinutes(1)
        val limit = now.plusHours(26)
        while (cursor.isBefore(limit)) {
            if (!isSilentAt(windows, defaultSilent, cursor)) return cursor
            cursor = cursor.plusMinutes(1)
        }
        return null
    }

    fun toEpochMillis(time: LocalDateTime?): Long? =
            time?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    suspend fun status(
            context: Context,
            chatId: String? = null,
            now: LocalDateTime = LocalDateTime.now()
    ): ReplyRhythmStatus {
        val prefs = ReplyRhythmPreferences.getInstance(context)
        if (!prefs.isEnabled()) {
            return ReplyRhythmStatus(false, false, false, null, "")
        }

        val manualUntil = prefs.getManualSilentUntil()
        if (manualUntil > System.currentTimeMillis()) {
            return ReplyRhythmStatus(true, true, false, manualUntil, "")
        }

        // 专属规则优先于全局
        val chatRule = if (chatId != null) prefs.getChatRule(chatId) else null
        val windows = chatRule?.windows ?: prefs.getWindows()
        val defaultSilent = chatRule?.defaultSilent ?: prefs.isDefaultSilent()

        val activeWindow =
                windows.firstOrNull {
                    it.type == ReplyWindowType.ACTIVE && windowMatches(it, now)
                }
        if (activeWindow != null) {
            return ReplyRhythmStatus(false, false, true, null, activeWindow.label)
        }

        val silentWindow =
                windows.firstOrNull {
                    it.type == ReplyWindowType.SILENT && windowMatches(it, now)
                }
        if (silentWindow != null) {
            val release = nextRelease(windows, defaultSilent, now)
            return ReplyRhythmStatus(
                    silent = true,
                    manual = false,
                    activeOverride = false,
                    releaseAtMillis = toEpochMillis(release),
                    label = silentWindow.label
            )
        }

        if (defaultSilent) {
            val release = nextRelease(windows, defaultSilent, now)
            return ReplyRhythmStatus(
                    silent = true,
                    manual = false,
                    activeOverride = false,
                    releaseAtMillis = toEpochMillis(release),
                    label = ""
            )
        }

        return ReplyRhythmStatus(false, false, false, null, "")
    }

    /** 当前是否应该攒着不回复 */
    suspend fun shouldHold(context: Context, chatId: String? = null): Boolean =
            status(context, chatId).silent
}
