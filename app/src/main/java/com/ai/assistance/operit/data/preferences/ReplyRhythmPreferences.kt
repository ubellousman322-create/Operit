package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.replyRhythmDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "reply_rhythm")

/** 窗口类型：SILENT 静默攒着，ACTIVE 强制立刻回复 */
@Serializable
enum class ReplyWindowType {
    SILENT,
    ACTIVE
}

/** 窗口重复方式 */
@Serializable
enum class ReplyWindowRepeat {
    EVERY_DAY,
    WORKDAYS,
    WEEKEND,
    CUSTOM_WEEKDAYS,
    ONCE
}

/**
 * 一个回复节奏窗口。
 * startMinute / endMinute 为当天 0 点起的分钟数；start > end 表示跨零点。
 * weekdays 为 ISO 星期值 1(周一)..7(周日)，仅 CUSTOM_WEEKDAYS 使用。
 * date 为 yyyy-MM-dd，仅 ONCE 使用。
 */
@Serializable
data class ReplyWindow(
        val type: ReplyWindowType = ReplyWindowType.SILENT,
        val startMinute: Int = 9 * 60,
        val endMinute: Int = 12 * 60,
        val repeat: ReplyWindowRepeat = ReplyWindowRepeat.WORKDAYS,
        val weekdays: List<Int> = emptyList(),
        val date: String = "",
        val label: String = ""
) {
    val crossesMidnight: Boolean
        get() = startMinute > endMinute
}

class ReplyRhythmPreferences private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: ReplyRhythmPreferences? = null

        fun getInstance(context: Context): ReplyRhythmPreferences =
                instance
                        ?: synchronized(this) {
                            instance
                                    ?: ReplyRhythmPreferences(context.applicationContext).also {
                                        instance = it
                                    }
                        }

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val KEY_ENABLED = booleanPreferencesKey("reply_rhythm_enabled")
        private val KEY_DEFAULT_SILENT = booleanPreferencesKey("reply_rhythm_default_silent")
        private val KEY_WINDOWS = stringPreferencesKey("reply_rhythm_windows")
        private val KEY_MAX_BATCH = stringPreferencesKey("reply_rhythm_max_batch")
        private val KEY_MANUAL_SILENT_UNTIL = stringPreferencesKey("reply_rhythm_manual_until")
    }

    val enabledFlow: Flow<Boolean> =
            context.replyRhythmDataStore.data.map { it[KEY_ENABLED] ?: false }

    val defaultSilentFlow: Flow<Boolean> =
            context.replyRhythmDataStore.data.map { it[KEY_DEFAULT_SILENT] ?: false }

    val windowsFlow: Flow<List<ReplyWindow>> =
            context.replyRhythmDataStore.data.map { prefs ->
                val raw = prefs[KEY_WINDOWS] ?: return@map emptyList()
                runCatching { JSON.decodeFromString<List<ReplyWindow>>(raw) }
                        .getOrDefault(emptyList())
            }

    val maxBatchFlow: Flow<Int> =
            context.replyRhythmDataStore.data.map {
                (it[KEY_MAX_BATCH] ?: "10").toIntOrNull()?.coerceIn(1, 50) ?: 10
            }

    /** 手动「现在开始攒着」的截止时间（毫秒），0 或过去时间表示未开启 */
    val manualSilentUntilFlow: Flow<Long> =
            context.replyRhythmDataStore.data.map {
                (it[KEY_MANUAL_SILENT_UNTIL] ?: "0").toLongOrNull() ?: 0L
            }

    suspend fun isEnabled(): Boolean = enabledFlow.first()

    suspend fun isDefaultSilent(): Boolean = defaultSilentFlow.first()

    suspend fun getWindows(): List<ReplyWindow> = windowsFlow.first()

    suspend fun getMaxBatch(): Int = maxBatchFlow.first()

    suspend fun getManualSilentUntil(): Long = manualSilentUntilFlow.first()

    suspend fun setEnabled(value: Boolean) {
        context.replyRhythmDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setDefaultSilent(value: Boolean) {
        context.replyRhythmDataStore.edit { it[KEY_DEFAULT_SILENT] = value }
    }

    suspend fun setMaxBatch(value: Int) {
        context.replyRhythmDataStore.edit {
            it[KEY_MAX_BATCH] = value.coerceIn(1, 50).toString()
        }
    }

    suspend fun saveWindows(windows: List<ReplyWindow>) {
        context.replyRhythmDataStore.edit { it[KEY_WINDOWS] = JSON.encodeToString(windows) }
    }

    suspend fun setManualSilentUntil(untilMillis: Long) {
        context.replyRhythmDataStore.edit { it[KEY_MANUAL_SILENT_UNTIL] = untilMillis.toString() }
    }

    suspend fun clearManualSilent() {
        context.replyRhythmDataStore.edit { it[KEY_MANUAL_SILENT_UNTIL] = "0" }
    }
}
