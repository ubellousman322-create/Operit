package com.ai.assistance.operit.ui.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 跨页跳转：收藏页写下要去的那条消息，聊天页读走之后再清空。 */
object PendingMessageJumpHandler {
    data class PendingJump(val chatId: String, val timestamp: Long)

    private val _pendingJump = MutableStateFlow<PendingJump?>(null)
    val pendingJump: StateFlow<PendingJump?> = _pendingJump

    fun setPendingJump(chatId: String, timestamp: Long) {
        _pendingJump.value = PendingJump(chatId, timestamp)
    }

    fun clearPendingJump() {
        _pendingJump.value = null
    }
}