package com.ai.assistance.operit.ui.features.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ManageHistory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.PatPreferences
import com.ai.assistance.operit.core.tools.defaultTool.standard.CookiePrivacyManager
import com.ai.assistance.operit.ui.main.components.GlassActionRow
import com.ai.assistance.operit.ui.main.components.rememberNavigationDrawerAppearance
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.launch

private data class SettingsGroupEntry(
        val key: String,
        val label: String,
        val icon: ImageVector
)

/** 「工具与设置」右侧栏里的分组入口页：玻璃卡片列出分组子项。 */
@Composable
fun SettingsGroupPageScreen(
        groupId: String,
        onOpen: (String) -> Unit,
        onGoBack: () -> Unit
) {
        val appearance = rememberNavigationDrawerAppearance()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var showClearCookieConfirm by remember { mutableStateOf(false) }
        var showPatDialog by remember { mutableStateOf(false) }
        var patSuffixDraft by remember { mutableStateOf(PatPreferences.getUserPatSuffix(context)) }
        val entries =
                when (groupId) {
                        "personalization" ->
                                listOf(
                                        SettingsGroupEntry(
                                                "user_preferences",
                                                stringResource(R.string.settings_user_preferences),
                                                Icons.Default.Face
                                        ),
                                        SettingsGroupEntry(
                                                "language",
                                                stringResource(R.string.language_settings),
                                                Icons.Default.Language
                                        ),
                                        SettingsGroupEntry(
                                                "theme",
                                                stringResource(R.string.settings_theme_appearance),
                                                Icons.Default.Palette
                                        ),
                                        SettingsGroupEntry(
                                                "global_display",
                                                stringResource(R.string.settings_global_display),
                                                Icons.Default.Visibility
                                        ),
                                        SettingsGroupEntry(
                                                "layout",
                                                stringResource(R.string.layout_adjustment),
                                                Icons.Default.AspectRatio
                                        ),
                                        SettingsGroupEntry(
                                                "pat",
                                                "拍一拍",
                                                Icons.Default.TouchApp
                                        )
                                )
                        "data_permissions" ->
                                listOf(
                                        SettingsGroupEntry(
                                                "tool_permissions",
                                                stringResource(R.string.settings_tool_permissions),
                                                Icons.Default.AdminPanelSettings
                                        ),
                                        SettingsGroupEntry(
                                                "backup",
                                                stringResource(R.string.settings_data_backup),
                                                Icons.Default.CloudUpload
                                        ),
                                        SettingsGroupEntry(
                                                "chat_history",
                                                stringResource(
                                                        R.string.settings_chat_history_management
                                                ),
                                                Icons.Default.ManageHistory
                                        ),
                                        SettingsGroupEntry(
                                                "token_stats",
                                                stringResource(R.string.settings_token_usage_stats),
                                                Icons.Default.Analytics
                                        )
                                )
                        "privacy_cleanup" ->
                                listOf(
                                        SettingsGroupEntry(
                                                "clear_cookies",
                                                stringResource(R.string.settings_clear_cookies),
                                                Icons.Default.DeleteSweep
                                        )
                                )
                        "prompts" ->
                                listOf(
                                        SettingsGroupEntry(
                                                "model_prompts",
                                                stringResource(R.string.settings_prompt_title),
                                                Icons.Default.ChatBubble
                                        ),
                                        SettingsGroupEntry(
                                                "waifu_mode",
                                                stringResource(R.string.waifu_mode_settings),
                                                Icons.Default.EmojiEmotions
                                        )
                                )
                        else -> emptyList()
                }
        val title =
                when (groupId) {
                        "personalization" -> "个性化"
                        "data_permissions" -> "数据和权限"
                        "privacy_cleanup" -> "隐私与数据清理"
                        "prompts" -> "提示词配置"
                        else -> "工具与设置"
                }
        Column(modifier = Modifier.fillMaxSize()) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        IconButton(onClick = onGoBack, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                        Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 6.dp)
                        )
                }
                HorizontalDivider()
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                                        .padding(
                                                bottom =
                                                        WindowInsets.navigationBars
                                                                .asPaddingValues()
                                                                .calculateBottomPadding() + 12.dp
                                        )
                ) {
                        entries.forEach { entry ->
                                GlassActionRow(
                                        icon = entry.icon,
                                        label = entry.label,
                                        appearance = appearance,
                                        onClick = {
                                                if (entry.key == "clear_cookies") {
                                                        showClearCookieConfirm = true
                                                } else if (entry.key == "pat") {
                                                        patSuffixDraft = PatPreferences.getUserPatSuffix(context)
                                                        showPatDialog = true
                                                } else {
                                                        onOpen(entry.key)
                                                }
                                        }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                        }
                }
        }
        if (showPatDialog) {
                AlertDialog(
                        onDismissRequest = { showPatDialog = false },
                        title = { Text(text = "拍一拍") },
                        text = {
                                Column {
                                        Text(
                                                text =
                                                        "双击我的头像时，聊天页中间会落一行字。这里填的会接在「我拍了拍 ave」后面，留空就什么都不接。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedTextField(
                                                value = patSuffixDraft,
                                                onValueChange = { patSuffixDraft = it.take(24) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                placeholder = { Text(text = "比如：的脑袋") }
                                        )
                                }
                        },
                        confirmButton = {
                                TextButton(
                                        onClick = {
                                                PatPreferences.setUserPatSuffix(context, patSuffixDraft)
                                                showPatDialog = false
                                        }
                                ) { Text(text = "保存") }
                        },
                        dismissButton = {
                                TextButton(onClick = { showPatDialog = false }) { Text(text = "取消") }
                        }
                )
        }
        if (showClearCookieConfirm) {
                AlertDialog(
                        onDismissRequest = { showClearCookieConfirm = false },
                        title = { Text(stringResource(R.string.clear_cookies_dialog_title)) },
                        text = { Text(stringResource(R.string.clear_cookies_dialog_message)) },
                        confirmButton = {
                                TextButton(
                                        onClick = {
                                                showClearCookieConfirm = false
                                                scope.launch {
                                                        try {
                                                                CookiePrivacyManager.clearAllCookies()
                                                                Toast.makeText(
                                                                                context,
                                                                                context.getString(
                                                                                        R.string.clear_cookies_success
                                                                                ),
                                                                                Toast.LENGTH_SHORT
                                                                        )
                                                                        .show()
                                                        } catch (e: Exception) {
                                                                AppLogger.e(
                                                                        "SettingsGroupPage",
                                                                        "Failed to clear cookies",
                                                                        e
                                                                )
                                                                Toast.makeText(
                                                                                context,
                                                                                context.getString(
                                                                                        R.string.clear_cookies_failed
                                                                                ),
                                                                                Toast.LENGTH_SHORT
                                                                        )
                                                                        .show()
                                                        }
                                                }
                                        }
                                ) {
                                        Text(stringResource(R.string.clear_cookies_confirm))
                                }
                        },
                        dismissButton = {
                                TextButton(onClick = { showClearCookieConfirm = false }) {
                                        Text(stringResource(android.R.string.cancel))
                                }
                        }
                )
        }
}
