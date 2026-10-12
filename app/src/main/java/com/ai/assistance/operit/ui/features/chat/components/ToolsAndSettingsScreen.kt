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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class ToolSettingsEntry(val label: String, val route: String)

/** 侧边栏底部「工具与设置」的独立页面。一级只放大类，避免再堆成长清单。 */
@Composable
fun ToolsAndSettingsScreen(onGoBack: () -> Unit, onOpen: (String) -> Unit) {
        var selectedCategory by remember { mutableStateOf<String?>(null) }
        val toolEntries =
                listOf(
                        ToolSettingsEntry("包管理", "packages"),
                        ToolSettingsEntry("权限", "tool_permissions"),
                        ToolSettingsEntry("工作流", "workflow"),
                        ToolSettingsEntry("工具箱", "toolbox"),
                        ToolSettingsEntry("助手配置", "assistant_config")
                )

        val title = if (selectedCategory == "tools") "工具" else "工具与设置"
        val handleBack = {
                if (selectedCategory == null) {
                        onGoBack()
                } else {
                        selectedCategory = null
                }
        }

        Column(modifier = Modifier.fillMaxSize()) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        IconButton(onClick = handleBack, modifier = Modifier.size(40.dp)) {
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
                                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                        if (selectedCategory == "tools") {
                                toolEntries.forEach { entry ->
                                        CategoryRow(
                                                label = entry.label,
                                                onClick = { onOpen(entry.route) }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                }
                        } else {
                                CategoryRow(
                                        label = "工具",
                                        icon = Icons.Default.Build,
                                        supportingText = "包管理、权限、工作流、工具箱、助手配置",
                                        onClick = { selectedCategory = "tools" }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                CategoryRow(
                                        label = "设置",
                                        icon = Icons.Default.Settings,
                                        supportingText = "进入原有设置页面，内容保持不变",
                                        onClick = { onOpen("settings") }
                                )
                        }
                }
        }
}

@Composable
private fun CategoryRow(
        label: String,
        icon: ImageVector? = null,
        supportingText: String? = null,
        onClick: () -> Unit
) {
        Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large
        ) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        if (icon != null) {
                                Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.size(14.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!supportingText.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                                text = supportingText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        }
                        Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
        }
}