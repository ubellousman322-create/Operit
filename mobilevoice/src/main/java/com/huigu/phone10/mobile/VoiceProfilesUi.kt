package com.huigu.phone10.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun VoiceProfilesDialog(settings: MobileSettings, busy: Boolean, error: String,
    onUse: (String) -> Unit, onRemove: (String) -> Unit,
    onCreate: () -> Unit, onClose: () -> Unit,
    localVoices: List<LocalVoice> = emptyList(), catalogFresh: Boolean = false,
    onRefresh: () -> Unit = {}, onLocalVoice: (LocalVoice) -> Unit = {}) {
    var confirmation by remember { mutableStateOf<VoiceProfile?>(null) }
    val action = confirmation
    if (action != null) {
        val profile = action
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text("移除「${profile.name}」？") },
            text = { Text("移除这个保存的方案，当前正在使用的语音配置仍会保留。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                confirmation = null
                onRemove(profile.id)
            }) { Text("移除") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text("取消") } })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("语音方案") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error.isNotBlank()) Text(error, color = ErpanColors.Rose, fontSize = 13.sp)
            Text("结束通话后选择声音，再开始通话即可使用。", fontSize = 13.sp)
            if (settings.profiles().isEmpty()) Text("还没有保存方案。填好语音服务后，可以保存一套，下次直接选择。")
            settings.profiles().forEach { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.name, fontSize = 17.sp)
                    Text(profile.description(), color = ErpanColors.Muted, fontSize = 12.sp)
                    Row {
                        TextButton(enabled = !busy, onClick = { onUse(profile.id) }) {
                            Text(if (settings.currentVoiceProfile()?.id == profile.id) "当前方案" else "使用")
                        }
                        TextButton(enabled = !busy, onClick = { confirmation = profile }) { Text("移除") }
                    }
                    HorizontalDivider(color = ErpanColors.Line)
                }
            }
            TextButton(enabled = !busy, onClick = onCreate) { Text("新建方案") }
            HorizontalDivider(color = ErpanColors.Line)
            Text("电脑上的声音", fontSize = 17.sp)
            TextButton(enabled = !busy && settings.localVoiceConnection() != null, onClick = onRefresh) {
                Text(if (busy) "正在处理…" else "刷新声音列表")
            }
            if (settings.localVoiceConnection() == null)
                Text("先在连接配置保存一套本机语音地址和密钥。", fontSize = 12.sp)
            if (localVoices.isNotEmpty() && !catalogFresh)
                Text("列表尚未更新，保留上次结果；使用时会重新确认。", fontSize = 12.sp)
            localVoices.forEach { voice ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(voice.name, fontSize = 16.sp)
                    Text("${voice.model} · ${voice.id}", fontSize = 12.sp, color = ErpanColors.Muted)
                    Text(if (voice.ready) "已就绪 · 支持：${voice.supportedModes.joinToString().ifBlank { "待确认" }}"
                        else "当前不可用 · 请在电脑端准备好后刷新", fontSize = 12.sp, color = ErpanColors.Muted)
                    TextButton(enabled = !busy && voice.ready &&
                        voice.supportedModes.any { it in setOf("none", "sentence") },
                        onClick = { onLocalVoice(voice) }) { Text("保存并使用") }
                }
            }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = onClose) { Text("关闭") } })
}

@Composable internal fun VoiceProfileNameDialog(initialName: String, busy: Boolean, error: String,
    onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initialName.take(40)) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("给新方案起个名字") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存当前的识别服务、合成服务和音色。密钥继续加密保存在本机。", fontSize = 13.sp)
            OutlinedTextField(name, onValueChange = { name = it.take(40) }, enabled = !busy,
                label = { Text("方案名称") }, placeholder = { Text("例如：日常百炼、家里本地声音") }, singleLine = true)
            if (error.isNotBlank()) Text(error, color = ErpanColors.Rose, fontSize = 13.sp)
        } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { onSave(name) }) { Text("保存并使用") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("取消") } })
}
