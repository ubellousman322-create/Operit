package com.ai.assistance.operit.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.theme.liquidGlass

private data class RightSidebarEntry(
        val key: String,
        val label: String,
        val icon: ImageVector
)

/** 右侧滑出侧边栏：工具与设置入口（顺序固定，玻璃卡片样式与抽屉菜单同源）。 */
@Composable
fun RightSidebarContent(
        onEntrySelected: (String) -> Unit,
        modifier: Modifier = Modifier
) {
        val appearance = rememberNavigationDrawerAppearance()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val entries =
                listOf(
                        RightSidebarEntry("account", "账号", Icons.Default.AccountCircle),
                        RightSidebarEntry("ob_memory", "OB 记忆库", Icons.Default.Psychology),
                        RightSidebarEntry("personalization", "个性化", Icons.Default.Palette),
                        RightSidebarEntry("packages", "包管理", Icons.Default.Extension),
                        RightSidebarEntry("workflow", "工作流", Icons.Default.AccountTree),
                        RightSidebarEntry("toolbox", "工具箱", Icons.Default.Apps),
                        RightSidebarEntry("permissions", "权限", Icons.Default.Build),
                        RightSidebarEntry("model_config", "模型与参数配置", Icons.Default.Api),
                        RightSidebarEntry("functional_config", "功能模型配置", Icons.Default.Tune),
                        RightSidebarEntry("speech_services", "语音服务配置", Icons.Default.SettingsEthernet),
                        RightSidebarEntry("prompts", "提示词配置", Icons.Default.Message),
                        RightSidebarEntry("context_summary", "上下文与总结", Icons.Default.Analytics),
                        RightSidebarEntry("assistant_config", "助手配置", Icons.Default.Tune),
                        RightSidebarEntry("data_permissions", "数据和权限", Icons.Default.Security),
                        RightSidebarEntry(
                                "privacy_cleanup",
                                "隐私与数据清理",
                                Icons.Default.DeleteSweep
                        ),
                        RightSidebarEntry(
                                "external_calls",
                                "外部调用",
                                Icons.Default.SettingsEthernet
                        )
                )
        Column(
                modifier =
                        modifier.fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 10.dp, end = 10.dp, top = 6.dp)
                                .padding(bottom = bottomInset + 10.dp)
        ) {
                Text(
                        text = "工具与设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = appearance.titleColor,
                        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp)
                )
                entries.forEach { entry ->
                        GlassActionRow(
                                icon = entry.icon,
                                label = entry.label,
                                appearance = appearance,
                                onClick = { onEntrySelected(entry.key) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                }
        }
}

/** 玻璃卡片样式的入口行，与抽屉菜单同源，可在二级设置页复用。 */
@Composable
fun GlassActionRow(
        icon: ImageVector,
        label: String,
        appearance: NavigationDrawerAppearance,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
) {
        val shape = RoundedCornerShape(18.dp)
        Surface(
                modifier =
                        modifier.fillMaxWidth()
                                .liquidGlass(
                                        enabled = appearance.buttonLiquidGlassEnabled,
                                        shape = shape,
                                        containerColor = appearance.buttonContainerColor,
                                        shadowElevation = 0.dp,
                                        borderWidth = 0.5.dp,
                                        blurRadius = 16.dp,
                                        overlayAlphaBoost = 0.04f,
                                        enableLens = false
                                ),
                onClick = onClick,
                shape = shape,
                color =
                        if (appearance.buttonLiquidGlassEnabled) {
                                Color.Transparent
                        } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
                        },
                tonalElevation = 0.dp
        ) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = appearance.itemColor,
                                modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                        Text(
                                text = "›",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                        )
                }
        }
}
