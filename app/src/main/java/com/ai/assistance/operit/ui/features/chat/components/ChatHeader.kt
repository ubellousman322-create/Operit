package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter

private const val CHAT_HEADER_CHARACTER_NAME_MAX_LENGTH = 12

private fun String.toChatHeaderName(maxLength: Int = CHAT_HEADER_CHARACTER_NAME_MAX_LENGTH): String {
        return if (length <= maxLength) this else take(maxLength) + "…"
}

@Composable
fun ChatHeader(
        showChatHistorySelector: Boolean,
        onToggleChatHistorySelector: () -> Unit,
        modifier: Modifier = Modifier,
        onLaunchFloatingWindow: () -> Unit = {},
        isFloatingMode: Boolean = false,
        historyIconColor: Int? = null,
        pipIconColor: Int? = null,
        runningTaskCount: Int = 0,
        activeCharacterName: String,
        activeCharacterAvatarUri: String?,
        onCharacterClick: () -> Unit,
        chatTitle: String = "",
        onTitleClick: () -> Unit = {}
) {
        val displayCharacterName = activeCharacterName.toChatHeaderName()

        Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = modifier.fillMaxWidth()
        ) {
                if (runningTaskCount >= 2) {
                        Surface(
                                onClick = onToggleChatHistorySelector,
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                        ) {
                                Row(
                                        modifier = Modifier.height(32.dp).padding(start = 6.dp, end = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.DarkMode,
                                                contentDescription =
                                                        if (showChatHistorySelector) stringResource(R.string.hide_history) else stringResource(R.string.show_history),
                                                tint =
                                                        historyIconColor?.let { Color(it) }
                                                                ?: MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                                text = runningTaskCount.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1
                                        )
                                }
                        }
                } else {
                        Box(
                                modifier =
                                        Modifier.size(32.dp)
                                                .background(
                                                        color =
                                                                if (showChatHistorySelector)
                                                                        MaterialTheme.colorScheme.primary
                                                                                .copy(alpha = 0.15f)
                                                                else Color.Transparent,
                                                        shape = CircleShape
                                                )
                        ) {
                                IconButton(
                                        onClick = onToggleChatHistorySelector,
                                        modifier = Modifier.matchParentSize()
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.DarkMode,
                                                contentDescription =
                                                        if (showChatHistorySelector) stringResource(R.string.hide_history) else stringResource(R.string.show_history),
                                                tint =
                                                        historyIconColor?.let { Color(it) }
                                                                ?: if (showChatHistorySelector)
                                                                        MaterialTheme.colorScheme.primary
                                                                else
                                                                        MaterialTheme.colorScheme.onSurface
                                                                                .copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                        )
                                }
                        }
                }

                // 头像和标题各自占位：标题吃掉中间剩余宽度，避免右侧功能挤成两行。
                Box(
                        modifier =
                                Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = onCharacterClick)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                ) {
                        if (activeCharacterAvatarUri != null) {
                                Image(
                                        painter = rememberAsyncImagePainter(model = Uri.parse(activeCharacterAvatarUri)),
                                        contentDescription = "Character Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                )
                        } else {
                                Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = "Character Avatar",
                                        modifier = Modifier.padding(5.dp)
                                )
                        }
                }
                Text(
                        text = if (chatTitle.isNotBlank()) chatTitle else displayCharacterName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                                Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onTitleClick)
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )

                IconButton(
                        onClick = onLaunchFloatingWindow,
                        enabled = !isFloatingMode,
                        modifier = Modifier.size(32.dp)
                ) {
                        Icon(
                                imageVector = Icons.Default.PictureInPicture,
                                contentDescription = "悬浮窗口",
                                tint =
                                        pipIconColor?.let { Color(it) }
                                                ?: MaterialTheme.colorScheme.onSurface.copy(
                                                        alpha = if (isFloatingMode) 0.35f else 0.7f
                                                ),
                                modifier = Modifier.size(20.dp)
                        )
                }
        }
}
