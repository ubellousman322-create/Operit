package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.FavoriteMessageEntry
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.theme.liquidGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一段“连着的”收藏：同一会话里消息序号相邻的收藏并成一组，像一小段对话。 */
data class FavoriteMessageGroup(
    val chatId: String,
    val chatTitle: String?,
    val entries: List<FavoriteMessageEntry>,
)

internal fun buildFavoriteMessageGroups(
    entries: List<FavoriteMessageEntry>,
): List<FavoriteMessageGroup> {
    val groups = mutableListOf<FavoriteMessageGroup>()
    val byChat = LinkedHashMap<String, MutableList<FavoriteMessageEntry>>()
    entries.forEach { entry -> byChat.getOrPut(entry.chatId) { mutableListOf() }.add(entry) }
    for ((chatId, chatEntries) in byChat) {
        val sorted = chatEntries.sortedBy { it.messageIndex }
        val title = sorted.firstOrNull()?.chatTitle
        var current = mutableListOf<FavoriteMessageEntry>()
        var previousIndex: Int? = null
        for (entry in sorted) {
            val previous = previousIndex
            if (previous != null && entry.messageIndex != previous + 1) {
                groups += FavoriteMessageGroup(chatId, title, current.toList())
                current = mutableListOf()
            }
            current += entry
            previousIndex = entry.messageIndex
        }
        if (current.isNotEmpty()) {
            groups += FavoriteMessageGroup(chatId, title, current.toList())
        }
    }
    return groups.sortedByDescending { group -> group.entries.maxOf { it.timestamp } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoriteMessagesScreen(
    onGoBack: () -> Unit,
    onJumpToFavorite: (chatId: String, timestamp: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { ChatHistoryManager.getInstance(context) }
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.76f).dp
    var entries by remember { mutableStateOf<List<FavoriteMessageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableStateOf(0) }
    var removalTarget by
        remember {
            mutableStateOf<Pair<FavoriteMessageGroup, FavoriteMessageEntry>?>(null)
        }

    LaunchedEffect(reloadToken) {
        loading = true
        entries = withContext(Dispatchers.IO) { manager.loadFavoriteMessageEntries() }
        loading = false
    }

    val groups = remember(entries) { buildFavoriteMessageGroups(entries) }

    val confirmRemoval: (FavoriteMessageGroup, FavoriteMessageEntry) -> Unit = { group, entry ->
        removalTarget = null
        scope.launch {
            withContext(Dispatchers.IO) {
                manager.setMessageFavorite(group.chatId, entry.timestamp, false)
            }
            reloadToken += 1
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.message_favorites),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    ) { innerPadding ->
        when {
            loading ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            groups.isEmpty() ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.StarOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.message_favorites_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 6.dp)) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.message_favorites_summary,
                                        entries.size,
                                        groups.size,
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.message_favorites_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                        }
                    }
                    items(
                        items = groups,
                        key = { group -> group.chatId + "#" + group.entries.first().timestamp },
                    ) { group ->
                        FavoriteMessageGroupCard(
                            group = group,
                            maxBubbleWidth = maxBubbleWidth,
                            onJumpToEntry = { entry ->
                                onJumpToFavorite(group.chatId, entry.timestamp)
                            },
                            onRequestRemove = { entry -> removalTarget = group to entry },
                        )
                    }
                }
        }
    }

    removalTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removalTarget = null },
            title = { Text(stringResource(R.string.message_favorites_remove_title)) },
            text = { Text(stringResource(R.string.message_favorites_remove_message)) },
            confirmButton = {
                TextButton(onClick = { confirmRemoval(target.first, target.second) }) {
                    Text(stringResource(R.string.message_favorites_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { removalTarget = null }) {
                    Text(stringResource(R.string.message_favorites_cancel))
                }
            },
        )
    }
}

@Composable
private fun FavoriteMessageGroupCard(
    group: FavoriteMessageGroup,
    maxBubbleWidth: androidx.compose.ui.unit.Dp,
    onJumpToEntry: (FavoriteMessageEntry) -> Unit,
    onRequestRemove: (FavoriteMessageEntry) -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .liquidGlass(
                    enabled = true,
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                    borderWidth = 0.6.dp,
                    blurRadius = 14.dp,
                    overlayAlphaBoost = 0.04f,
                    enableLens = false,
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text =
                    group.chatTitle?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.message_favorites_untitled_chat),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatFavoriteTimestamp(group.entries.last().timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        group.entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            FavoriteMessageBubble(
                entry = entry,
                maxBubbleWidth = maxBubbleWidth,
                onClick = { onJumpToEntry(entry) },
                onLongClick = { onRequestRemove(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteMessageBubble(
    entry: FavoriteMessageEntry,
    maxBubbleWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isUser = entry.sender == "user"
    val isThinking = !isUser && entry.previewContent.trimStart().startsWith("<think>")
    val bubbleColor =
        when {
            isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            isThinking -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        }
    val bubbleShape =
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = if (isUser) 18.dp else 7.dp,
            bottomEnd = if (isUser) 7.dp else 18.dp,
        )

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier.align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = maxBubbleWidth)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .background(bubbleColor, bubbleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = favoritePreviewText(entry),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isThinking) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun favoritePreviewText(entry: FavoriteMessageEntry): String {
    val text = entry.previewContent.replace('\n', ' ').trim()
    if (text.isEmpty()) {
        return "…"
    }
    return if (entry.contentLength > text.length) "$text…" else text
}

private fun formatFavoriteTimestamp(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))