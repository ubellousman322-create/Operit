package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.chatModelOverrideDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "chat_model_overrides")

/** 单个对话窗口的模型覆盖设置。configId 为空表示跟随角色卡/全局。 */
data class ChatModelOverride(
    val configId: String?,
    val modelIndex: Int = 0,
)

/** 按对话窗口保存模型选择，不影响角色卡绑定。 */
class ChatModelOverrideManager private constructor(private val context: Context) {

    private fun configIdKey(chatId: String) =
        stringPreferencesKey("chat_${chatId}_model_config_id")

    private fun modelIndexKey(chatId: String) =
        intPreferencesKey("chat_${chatId}_model_index")

    fun observeOverride(chatId: String): Flow<ChatModelOverride> =
        context.chatModelOverrideDataStore.data.map { preferences ->
            ChatModelOverride(
                configId = preferences[configIdKey(chatId)]?.takeIf { it.isNotBlank() },
                modelIndex = (preferences[modelIndexKey(chatId)] ?: 0).coerceAtLeast(0),
            )
        }

    suspend fun getOverride(chatId: String): ChatModelOverride? {
        if (chatId.isBlank()) return null
        val preferences = context.chatModelOverrideDataStore.data.first()
        val configId = preferences[configIdKey(chatId)]?.takeIf { it.isNotBlank() } ?: return null
        return ChatModelOverride(
            configId = configId,
            modelIndex = (preferences[modelIndexKey(chatId)] ?: 0).coerceAtLeast(0),
        )
    }

    suspend fun setOverride(chatId: String, configId: String, modelIndex: Int) {
        if (chatId.isBlank() || configId.isBlank()) return
        context.chatModelOverrideDataStore.edit { preferences ->
            preferences[configIdKey(chatId)] = configId
            preferences[modelIndexKey(chatId)] = modelIndex.coerceAtLeast(0)
        }
    }

    suspend fun clearOverride(chatId: String) {
        if (chatId.isBlank()) return
        context.chatModelOverrideDataStore.edit { preferences ->
            preferences.remove(configIdKey(chatId))
            preferences.remove(modelIndexKey(chatId))
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ChatModelOverrideManager? = null

        fun getInstance(context: Context): ChatModelOverrideManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE
                    ?: ChatModelOverrideManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
