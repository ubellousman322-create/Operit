package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 侧边栏底部「工具与设置」点进来的页面： 把从侧边栏清出去的入口都收在这里，分 工具 / 设置 两组。
 */
@Composable
fun ToolsAndSettingsScreen(onGoBack: () -> Unit, onOpen: (String) -> Unit) {
        val groups =
                listOf(
                        "工具" to
                                listOf(
                                        "工作流" to "workflow",
                                        "包管理" to "packages",
                                        "工具箱" to "toolbox"
                                ),
                        "插件" to
                                listOf(
                                        "OB 记忆" to "memory",
                                        "AI 的电子嘴巴" to "plugins"
                                ),
                        "设置" to
                                listOf(
                                        "助手配置" to "assistant_config",
                                        "模型配置" to "model_config",
                                        "提示词" to "prompts",
                                        "外观" to "appearance",
                                        "工具权限" to "tool_permissions",
                                        "存储与备份" to "backup",
                                        "Token 配置" to "token_config",
                                        "设置" to "settings"
                                )
                )
        Column(modifier = Modifier.fillMaxSize()) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        IconButton(onClick = onGoBack, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                        Text(
                                text = "工具与设置",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 6.dp)
                        )
                }
                HorizontalDivider()
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        groups.forEach { group ->
                                val title = group.first
                                val entries = group.second
                                Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier =
                                                Modifier.padding(
                                                        start = 16.dp,
                                                        top = 16.dp,
                                                        bottom = 4.dp
                                                )
                                )
                                entries.forEach { entry ->
                                        val label = entry.first
                                        val route = entry.second
                                        Row(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .clickable { onOpen(route) }
                                                                .padding(
                                                                        horizontal = 16.dp,
                                                                        vertical = 14.dp
                                                                ),
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Text(
                                                        text = label,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                )
                                        }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                        }
                }
        }
}