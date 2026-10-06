package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.ChatModelOverride
import com.ai.assistance.operit.data.preferences.ChatModelOverrideManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun rememberChatScopedModelOverride(chatId: String?): ChatModelOverride? {
    val context = LocalContext.current
    val flow: Flow<ChatModelOverride?> =
        remember(chatId) {
            val id = chatId
            if (id.isNullOrBlank()) {
                flowOf<ChatModelOverride?>(null)
            } else {
                ChatModelOverrideManager.getInstance(context).observeOverride(id)
            }
        }
    return flow.collectAsState(initial = null).value
}

@Composable
internal fun ChatModelScopeToggleItem(
    chatLocalModelOnly: Boolean,
    hasChatScopedOverride: Boolean,
    onChatLocalModelOnlyChange: (Boolean) -> Unit,
    onClearChatScope: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_model_scope_local_only),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.chat_model_scope_local_only_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = chatLocalModelOnly,
                onCheckedChange = onChatLocalModelOnlyChange,
            )
        }
        if (hasChatScopedOverride) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_model_scope_local_active),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearChatScope) {
                    Text(
                        text = stringResource(R.string.chat_model_scope_clear),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
