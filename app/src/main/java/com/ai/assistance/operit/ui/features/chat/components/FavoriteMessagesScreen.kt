package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.FavoriteMessageEntry
import com.ai.assistance.operit.data.repository.ChatHistoryManager
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteMessagesScreen(
    onGoBack: () -> Unit,
    onJumpToFavorite: (chatId: String, timestamp: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { ChatHistoryManager.getInstance(context) }
    var entries by remember { mutableStateOf<List<FavoriteMessageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        entries = withContext(Dispatchers.IO) { manager.loadFavoriteMessageEntries() }
        loading = false
    }

    val groups = remember(entries) { buildFavoriteMessageGroups(entries) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.message_favorites)) },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
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
                    Text(
                        text = stringResource(R.string.message_favorites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = groups,
                        key = { group -> group.chatId + "#" + group.entries.first().timestamp },
                    ) { group ->
                        FavoriteMessageGroupCard(
                            group = group,
                            onJumpToEntry = { entry ->
                                onJumpToFavorite(group.chatId, entry.timestamp)
                            },
                            onRemoveEntry = { entry ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        manager.setMessageFavorite(
                                            group.chatId,
                                            entry.timestamp,
                                            false,
                                        )
                                    }
                                    reloadToken += 1
                                }
                            },
                        )
                    }
                }
        }
    }
}

@Composable
private fun FavoriteMessageGroupCard(
    group: FavoriteMessageGroup,
    onJumpToEntry: (FavoriteMessageEntry) -> Unit,
    onRemoveEntry: (FavoriteMessageEntry) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            group.entries.forEach { entry ->
                FavoriteMessageRow(
                    entry = entry,
                    onClick = { onJumpToEntry(entry) },
                    onRemove = { onRemoveEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun FavoriteMessageRow(
    entry: FavoriteMessageEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = favoriteSpeakerLabel(entry.sender),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(30.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = favoritePreviewText(entry),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(R.string.message_favorites_remove),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun favoriteSpeakerLabel(sender: String): String =
    when (sender) {
        "user" -> stringResource(R.string.message_favorites_sender_user)
        "summary" -> stringResource(R.string.message_favorites_sender_summary)
        else -> stringResource(R.string.message_favorites_sender_ai)
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