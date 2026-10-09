package com.ai.assistance.operit.data.model

/** 收藏页用的轻量条目：一条打了星的消息，连同它属于哪个会话。 */
data class FavoriteMessageEntry(
    val chatId: String,
    val chatTitle: String? = null,
    val messageIndex: Int = 0,
    val timestamp: Long,
    val sender: String,
    val previewContent: String,
    val contentLength: Int,
    val displayMode: String,
    val isFavorite: Boolean = true,
) {
    val resolvedDisplayMode: ChatMessageDisplayMode
        get() =
            runCatching { ChatMessageDisplayMode.valueOf(displayMode) }
                .getOrDefault(ChatMessageDisplayMode.NORMAL)
}