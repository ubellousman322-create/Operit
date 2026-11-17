package com.ai.assistance.operit.ui.features.chat.components.replyrhythm

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.data.preferences.ReplyRhythmPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.preferences.ReplyWindow
import com.ai.assistance.operit.data.preferences.ReplyWindowRepeat
import com.ai.assistance.operit.data.preferences.ReplyWindowType
import kotlinx.coroutines.launch

private fun formatMinuteOfDay(minute: Int): String {
    val m = ((minute % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun repeatLabel(repeat: ReplyWindowRepeat, weekdays: List<Int>): String =
        when (repeat) {
            ReplyWindowRepeat.EVERY_DAY -> "每天"
            ReplyWindowRepeat.WORKDAYS -> "周一至周五"
            ReplyWindowRepeat.WEEKEND -> "周末"
            ReplyWindowRepeat.CUSTOM_WEEKDAYS ->
                if (weekdays.isEmpty()) "自选星期" else "周" + weekdays.sorted().joinToString("")
            ReplyWindowRepeat.ONCE -> "单次"
        }

/**
 * 回复节奏设置：总开关、窗口列表、增删。
 * 窗口类型：静默（攒着）/ 不静默（在那段时间里不攒）。
 */
@Composable
fun ReplyRhythmSettingsDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ReplyRhythmPreferences.getInstance(context) }
    val chatHistoryManager = remember { ChatHistoryManager.getInstance(context) }
    val chatHistories by chatHistoryManager.chatHistoriesFlow.collectAsState(initial = emptyList())

    var enabled by remember { mutableStateOf(false) }
    var defaultSilent by remember { mutableStateOf(false) }
    var windows by remember { mutableStateOf<List<ReplyWindow>>(emptyList()) }
    var exemptIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<ReplyWindow?>(null) }

    fun reload() {
        scope.launch {
            enabled = prefs.isEnabled()
            defaultSilent = prefs.isDefaultSilent()
            windows = prefs.getWindows()
            exemptIds = prefs.getExemptChatIds()
        }
    }

    LaunchedEffect(Unit) { reload() }

    Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                            text = "回复节奏",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用", style = MaterialTheme.typography.bodyLarge)
                        Text(
                                "关掉之后，所有时段都不生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                            checked = enabled,
                            onCheckedChange = { value ->
                                enabled = value
                                scope.launch {
                                    prefs.setEnabled(value)
                                    reload()
                                }
                            },
                    )
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("时段", style = MaterialTheme.typography.titleSmall)
                            Text(
                                    "这段时间攒着、那段时间照常回；「照常回」优先",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    if (windows.isEmpty()) {
                        item {
                            Text(
                                    "还没有时段。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(windows) { window ->
                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .background(
                                                        MaterialTheme.colorScheme.surfaceVariant.copy(
                                                                alpha = 0.4f
                                                        ),
                                                        RoundedCornerShape(12.dp),
                                                )
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text =
                                                (if (window.type == ReplyWindowType.SILENT) "攒着"
                                                else "照常回") +
                                                        "  " +
                                                        formatMinuteOfDay(window.startMinute) +
                                                        " - " +
                                                        formatMinuteOfDay(window.endMinute),
                                        style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                        text = repeatLabel(window.repeat, window.weekdays),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                    onClick = {
                                        val next = windows.toMutableList()
                                        next.remove(window)
                                        windows = next
                                        scope.launch {
                                            prefs.saveWindows(next)
                                            reload()
                                        }
                                    }
                            ) {
                                Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { editing = ReplyWindow() }) { Text("添加时段") }
                    }
                    item {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("不参与静默的对话", style = MaterialTheme.typography.titleSmall)
                            Text(
                                    "勾上的对话，什么时段都立刻回",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    if (chatHistories.isEmpty()) {
                        item {
                            Text(
                                    "还没有对话。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(chatHistories) { chat ->
                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .clickable {
                                                    val checked = chat.id !in exemptIds
                                                    val next =
                                                            if (checked) exemptIds + chat.id
                                                            else exemptIds - chat.id
                                                    exemptIds = next
                                                    scope.launch {
                                                        prefs.setChatExempt(chat.id, checked)
                                                    }
                                                }
                                                .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                    checked = chat.id in exemptIds,
                                    onCheckedChange = { checked ->
                                        val next =
                                                if (checked) exemptIds + chat.id
                                                else exemptIds - chat.id
                                        exemptIds = next
                                        scope.launch { prefs.setChatExempt(chat.id, checked) }
                                    },
                            )
                            Text(
                                    text = chat.title.ifBlank { "未命名对话" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                            "不在这些时段里：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(
                            selected = !defaultSilent,
                            onClick = {
                                scope.launch {
                                    prefs.setDefaultSilent(false)
                                    reload()
                                }
                            },
                            label = { Text("正常回复") },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                            selected = defaultSilent,
                            onClick = {
                                scope.launch {
                                    prefs.setDefaultSilent(true)
                                    reload()
                                }
                            },
                            label = { Text("也攒着") },
                    )
                }
            }
        }
    }

    val current = editing
    if (current != null) {
        ReplyWindowEditor(
                initial = current,
                onDismiss = { editing = null },
                onConfirm = { window ->
                    val next = windows + window
                    windows = next
                    editing = null
                    scope.launch {
                        prefs.saveWindows(next)
                        reload()
                    }
                },
        )
    }
}

@Composable
private fun ReplyWindowEditor(
        initial: ReplyWindow,
        onDismiss: () -> Unit,
        onConfirm: (ReplyWindow) -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial.type) }
    var startMinute by remember { mutableStateOf(initial.startMinute) }
    var endMinute by remember { mutableStateOf(initial.endMinute) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var weekdays by remember { mutableStateOf(initial.weekdays) }

    fun pickTime(currentMinute: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
                        context,
                        { _, hour, minute -> onPicked(hour * 60 + minute) },
                        currentMinute / 60,
                        currentMinute % 60,
                        true,
                )
                .show()
    }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("添加时段") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .clickable { type = ReplyWindowType.SILENT },
                            verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                                selected = type == ReplyWindowType.SILENT,
                                onClick = { type = ReplyWindowType.SILENT },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("这段时间攒着", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                    "你发的先存着，过了再一起回",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .clickable { type = ReplyWindowType.ACTIVE },
                            verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                                selected = type == ReplyWindowType.ACTIVE,
                                onClick = { type = ReplyWindowType.ACTIVE },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("这段时间照常回", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                    "就算落在「攒着」里，这段时间也立刻回",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickTime(startMinute) { startMinute = it } }) {
                            Text("开始 ${formatMinuteOfDay(startMinute)}")
                        }
                        Text("→")
                        TextButton(onClick = { pickTime(endMinute) { endMinute = it } }) {
                            Text("结束 ${formatMinuteOfDay(endMinute)}")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                                selected = repeat == ReplyWindowRepeat.WORKDAYS,
                                onClick = { repeat = ReplyWindowRepeat.WORKDAYS },
                                label = { Text("工作日") },
                        )
                        FilterChip(
                                selected = repeat == ReplyWindowRepeat.EVERY_DAY,
                                onClick = { repeat = ReplyWindowRepeat.EVERY_DAY },
                                label = { Text("每天") },
                        )
                        FilterChip(
                                selected = repeat == ReplyWindowRepeat.WEEKEND,
                                onClick = { repeat = ReplyWindowRepeat.WEEKEND },
                                label = { Text("周末") },
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..7).forEach { day ->
                            val selected = weekdays.contains(day)
                            Box(
                                    modifier =
                                            Modifier.width(32.dp)
                                                    .height(32.dp)
                                                    .background(
                                                            if (selected &&
                                                                    repeat ==
                                                                            ReplyWindowRepeat
                                                                                    .CUSTOM_WEEKDAYS
                                                            )
                                                                    MaterialTheme.colorScheme.primary
                                                            else
                                                                    MaterialTheme.colorScheme
                                                                            .surfaceVariant,
                                                            RoundedCornerShape(16.dp),
                                                    )
                                                    .clickable {
                                                        val next = weekdays.toMutableList()
                                                        if (selected) next.remove(day) else next.add(day)
                                                        weekdays = next
                                                        repeat =
                                                                ReplyWindowRepeat
                                                                        .CUSTOM_WEEKDAYS
                                                    },
                                    contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                        text = "${day}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                                if (selected &&
                                                        repeat ==
                                                                ReplyWindowRepeat.CUSTOM_WEEKDAYS
                                                )
                                                        MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            onConfirm(
                                    ReplyWindow(
                                            type = type,
                                            startMinute = startMinute,
                                            endMinute = endMinute,
                                            repeat = repeat,
                                            weekdays = weekdays
                                    )
                            )
                        }
                ) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
