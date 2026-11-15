package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.util.LocaleUtils

/**
 * 工具开关面板。
 *
 * 数据源是 [SystemToolPrompts.getManageableToolPrompts]，现在覆盖提示词构建实际使用的全部分类
 * （主类 4 个 + SystemToolPromptsInternal 的 12 个拓展分类），不再是原来那 5 个基础分类。
 *
 * 关掉某项 = 该工具的说明从 system prompt 消失、function 定义从请求体消失。
 * 这里只控制“模型能不能看见”，工具实现本身始终留在 AIToolHandler 里。
 */
@Composable
fun ToolPromptManagerDialog(
    visible: Boolean,
    toolPromptVisibility: Map<String, Boolean>,
    toolOrder: List<String> = emptyList(),
    onSaveToolPromptVisibilityMap: (Map<String, Boolean>) -> Unit,
    onSaveToolOrder: (List<String>) -> Unit = {},
    onDismissRequest: () -> Unit,
    onManagePackagesClick: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val useEnglish = remember(context) { !LocaleUtils.usesChineseContent(context) }

    val allTools =
        remember(useEnglish, toolOrder) {
            SystemToolPrompts.getManageableToolPrompts(useEnglish, toolOrder)
        }
    val grouped = remember(allTools) { allTools.groupBy { it.categoryName } }

    var query by remember { mutableStateOf("") }
    var collapsedCategories by remember { mutableStateOf(emptySet<String>()) }
    var localVisibility by remember(toolPromptVisibility) { mutableStateOf(toolPromptVisibility) }

    fun isOn(tool: SystemToolPrompts.ManageableToolPrompt): Boolean =
        localVisibility[tool.name] ?: tool.defaultEnabled

    fun commit(updates: Map<String, Boolean>) {
        if (updates.isEmpty()) return
        val next = localVisibility + updates
        localVisibility = next
        onSaveToolPromptVisibilityMap(next)
    }

    val searching = query.isNotBlank()
    val visibleGroups: List<Pair<String, List<SystemToolPrompts.ManageableToolPrompt>>> =
        remember(grouped, query) {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) {
                grouped.toList()
            } else {
                grouped
                    .map { entry ->
                        val categoryHit = entry.key.lowercase().contains(needle)
                        val matched =
                            if (categoryHit) entry.value
                            else entry.value.filter { it.name.lowercase().contains(needle) }
                        entry.key to matched
                    }
                    .filter { it.second.isNotEmpty() }
            }
        }

    val lazyListState = rememberLazyListState()
    val enabledTotal = allTools.count { isOn(it) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_close))
            }
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.tool_prompt_manager_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text =
                        if (useEnglish) {
                            "$enabledTotal / ${allTools.size} enabled"
                        } else {
                            "已开启 $enabledTotal / ${allTools.size}"
                        },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
            ) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = {
                            Text(
                                text = if (useEnglish) "Search tool or category" else "搜索工具或分类",
                                fontSize = 12.sp,
                            )
                        },
                    )
                }

                item(key = "bulk") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { commit(allTools.associate { it.name to true }) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (useEnglish) "Enable all" else "全部开启",
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(
                            onClick = { commit(allTools.associate { it.name to false }) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (useEnglish) "Disable all" else "全部关闭",
                                fontSize = 12.sp,
                            )
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    )
                }

                visibleGroups.forEach { entry ->
                    val categoryName = entry.first
                    val tools = entry.second
                    val enabledCount = tools.count { isOn(it) }
                    val collapsed = !searching && collapsedCategories.contains(categoryName)

                    item(key = "header:$categoryName") {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        collapsedCategories =
                                            if (collapsedCategories.contains(categoryName)) {
                                                collapsedCategories - categoryName
                                            } else {
                                                collapsedCategories + categoryName
                                            }
                                    }
                                    .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (collapsed) "\u25B8" else "\u25BE",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(
                                text = categoryName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "$enabledCount/${tools.size}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 2.dp),
                            )
                            Switch(
                                checked = enabledCount == tools.size,
                                onCheckedChange = { checked ->
                                    commit(tools.associate { it.name to checked })
                                },
                                modifier = Modifier.scale(0.68f),
                            )
                        }
                    }

                    if (!collapsed) {
                        items(items = tools, key = { tool -> "tool:" + tool.name }) { tool ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tool.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Switch(
                                    checked = isOn(tool),
                                    onCheckedChange = { checked ->
                                        commit(mapOf(tool.name to checked))
                                    },
                                    modifier = Modifier.scale(0.7f),
                                )
                            }
                        }
                    }

                    item(key = "divider:$categoryName") {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                        )
                    }
                }

                item(key = "packages") {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(36.dp)
                                .clickable(onClick = onManagePackagesClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.manage_packages),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        },
    )
}
