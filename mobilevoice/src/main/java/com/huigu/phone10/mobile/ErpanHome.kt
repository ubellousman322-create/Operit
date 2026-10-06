package com.huigu.phone10.mobile

import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun ErpanHome(settings: MobileSettings, state: VoiceState, avatar: Drawable?,
    appearance: AvatarAppearance,
    busy: Boolean, notice: String, onStart: () -> Unit, onEnd: () -> Unit, onMic: () -> Unit,
    onOverlay: (Boolean) -> Unit, onSmart: (Boolean) -> Unit, onConfirmBeforeSend: (Boolean) -> Unit,
    onVoiceInterruption: (Boolean) -> Unit, onReviewDraft: () -> Unit,
    onAvatar: () -> Unit,
    onConfig: () -> Unit, onLogs: () -> Unit, onAbout: () -> Unit,
    onProfiles: () -> Unit, onCaptions: (Boolean) -> Unit,
    onListenOnly: (Boolean) -> Unit, onInterrupt: () -> Unit,
    onIncoming: (Boolean) -> Unit) {
    var showTitle by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val configured = configurationIssues(settings).isEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 17.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("耳畔", fontFamily = FontFamily.Serif, fontSize = 34.sp)
                Box(Modifier.width(32.dp).height(2.dp).background(ErpanColors.Rose))
            }
            Text("让对话 · 靠近一点", color = ErpanColors.Muted, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 5.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.37f).aspectRatio(1f).border(0.7.dp, ErpanColors.Line, CircleShape).padding(10.dp)
                .border(0.7.dp, ErpanColors.Line, CircleShape).padding(4.dp).clip(CircleShape)
                .background(ErpanColors.Blush).clickable(enabled = !state.running, onClick = onAvatar), contentAlignment = Alignment.Center) {
                AndroidView(factory = { context ->
                    Phone10MicView(context).apply {
                        render(Phone10MicrophoneState(enabled = true))
                        isClickable = false
                    }
                }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.previewScale = minOf(2f, 90f / appearance.windowDp)
                    view.appearance = appearance
                    if (view.avatar !== avatar) view.avatar = avatar
                })
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center) {
                    Text("头像与光效", color = Color.White, fontSize = 11.sp)
                }
            }
            Surface(Modifier.weight(0.63f), color = ErpanColors.Paper, shape = RoundedCornerShape(15.dp),
                border = BorderStroke(0.8.dp, ErpanColors.Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("当前状态", fontSize = 16.sp, fontFamily = FontFamily.Serif)
                    Box(Modifier.width(23.dp).height(1.5.dp).background(ErpanColors.Rose))
                    Text(settings.displayChat(), fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 36.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { showTitle = true })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(9.dp).background(if (state.running && state.micEnabled) ErpanColors.Rose else Color(0xFF9A9AA0), CircleShape))
                        Text(state.homeStatus(configured), color = ErpanColors.Muted, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = ErpanColors.Line, thickness = 0.6.dp)
                    Text("声音回手机 · 文字回 Operit", color = ErpanColors.Muted, fontSize = 10.sp, maxLines = 2)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !settings.listenOnly, onClick = { onListenOnly(false) }, enabled = !state.running && !busy,
                    label = { Text("语音通话") })
                FilterChip(selected = settings.listenOnly, onClick = { onListenOnly(true) }, enabled = !state.running && !busy,
                    label = { Text("只听回复") })
                FilterChip(selected = settings.allowIncoming, onClick = { onIncoming(!settings.allowIncoming) }, enabled = !busy,
                    label = { Text("允许来电") })
            }
            Text(if (settings.speech.clientSegmentedTts) "首个断句标点到达即提交合成，后续合段；实际出声仍需等待模型生成。"
            else if (settings.speech.wholeReplyTts) {
                if (settings.listenOnly) "在 Operit 所选聊天中打字，整条回复写完后开始朗读；无需开麦。"
                else "你说话，等待 AI 整条回复写完后播放声音。"
            } else if (settings.listenOnly) "在 Operit 所选聊天中打字，回复生成时陆续朗读；无需开麦。" else "你说话，AI 用语音回复。",
                color = ErpanColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CallButton(if (settings.listenOnly) "开始听回复" else "开始通话", ErpanIcon.PHONE, enabled = !state.running && !busy, primary = true,
                modifier = Modifier.weight(1.04f), onClick = onStart)
            CallButton("结束语音", ErpanIcon.END, enabled = state.running, primary = false,
                modifier = Modifier.weight(0.96f), onClick = onEnd)
        }
        if (state.running && state.listenOnly) TextButton(onClick = onInterrupt) { Text("停止播放当前及排队语音") }
        if (notice.isNotBlank()) Text(notice, color = ErpanColors.Rose, fontSize = 13.sp, lineHeight = 19.sp)
        if (state.running || state.message.contains("失败") || state.message.contains("占用"))
            Text(state.message, color = ErpanColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (state.pendingDraft != null) Surface(onClick = onReviewDraft, shape = RoundedCornerShape(14.dp),
            color = ErpanColors.Blush, border = BorderStroke(0.8.dp, ErpanColors.Rose), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("语音待确认 · 尚未发送", fontSize = 17.sp, color = ErpanColors.Ink)
                Text("点开悬浮待发栏改字或发送；挂断会取消。", fontSize = 13.sp, color = ErpanColors.Muted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            SectionTitle("声音")
            ErpanNavigationCard("当前声音", value = settings.displayVoice(),
                subtitle = listOfNotNull(settings.currentVoiceProfile()?.name,
                    if (state.running) "结束语音后可切换" else "点此选择语音方案").joinToString(" · "),
                enabled = !state.running && !busy, onClick = onProfiles)
        }
        ErpanNavigationCard("头像与光效", "动图 · 大小 · 裁剪 · 光效", onClick = onAvatar)
        ErpanNavigationCard("连接配置", "聊天 · 识别 · 合成", onClick = onConfig)
        val panel = remember { GenericShape { size, _ ->
            val cut = minOf(size.width * 0.06f, size.height * 0.1f)
            moveTo(0f, 0f); lineTo(size.width - cut, 0f); lineTo(size.width, cut)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        } }
        Surface(color = ErpanColors.Coal, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().clip(panel)) {
            Column(Modifier.padding(horizontal = 19.dp, vertical = 22.dp)) {
                SectionTitle("通话设置", dark = true)
                Spacer(Modifier.height(12.dp))
                ErpanToggle("麦克风", state.micEnabled, enabled = !settings.listenOnly && state.running && !state.changing && !busy,
                    onChange = { onMic() })
                HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.3f), thickness = 0.6.dp)
                ErpanToggle("后台悬浮球", if (state.running) state.overlayVisible else settings.overlayEnabled,
                    enabled = !busy && !settings.listenOnly, helper = "语音通话时显示开关麦悬浮球", onChange = onOverlay)
                HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.3f), thickness = 0.6.dp)
                ErpanToggle("悬浮字幕", if (state.running) state.captionsVisible else settings.captionsEnabled,
                    enabled = !busy, helper = "显示正在生成的回复；可拖动、收起，文字可能比声音稍快。", onChange = onCaptions)
                HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.3f), thickness = 0.6.dp)
                ErpanToggle("发送前确认", settings.confirmBeforeSend, enabled = !settings.listenOnly && !state.running && !busy,
            helper = "识别后在悬浮窗改字，点发送才提交；通话中不可切换。", onChange = onConfirmBeforeSend)
                HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.3f), thickness = 0.6.dp)
                ErpanToggle("智能结束判断", settings.smartEndpoint && !settings.confirmBeforeSend,
                    enabled = !settings.listenOnly && !state.running && !busy && !settings.confirmBeforeSend,
                    helper = if (settings.confirmBeforeSend) "手动确认期间暂停判断服务；关闭发送前确认后恢复原设置。"
                        else if (settings.smartEndpoint) "使用额外文本模型判断，可能增加等待和费用。" else "关闭时，约 0.55 秒静音提交",
                    onChange = onSmart)
                HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.3f), thickness = 0.6.dp)
                ErpanToggle("人声打断", !settings.disableVoiceInterruption, enabled = !settings.listenOnly && !busy,
                    helper = if (settings.disableVoiceInterruption) "关闭时，等 AI 回复和语音结束后再说。" else "开启时，直接开口即可打断 AI。",
                    onChange = onVoiceInterruption)
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = ErpanColors.CoalMuted.copy(alpha = 0.3f), thickness = 0.6.dp)
                TextButton(onClick = onLogs, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("查看日志", fontFamily = FontFamily.Serif, fontSize = 18.sp, color = ErpanColors.CoalText)
                    Spacer(Modifier.width(9.dp)); LineIcon(ErpanIcon.ARROW, ErpanColors.CoalText, Modifier.size(18.dp))
                }
            }
        }
        Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(14.dp), color = ErpanColors.Paper,
            border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("扩展功能", Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 21.sp)
                LineIcon(if (expanded) ErpanIcon.DOWN else ErpanIcon.PLUS)
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("更多接入方式会在这里加入。", color = ErpanColors.Muted, fontSize = 13.sp)
            TextButton(onClick = onAbout) { Text("关于耳畔") }
        }
    }
    if (showTitle) AlertDialog(onDismissRequest = { showTitle = false }, title = { Text("当前聊天") },
        text = { Text(settings.displayChat()) }, confirmButton = { TextButton(onClick = { showTitle = false }) { Text("关闭") } })
}

@Composable private fun CallButton(label: String, icon: ErpanIcon, enabled: Boolean, primary: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val tint = if (enabled) { if (primary) ErpanColors.Rose else ErpanColors.Ink } else Color(0xFFA0A0A5)
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 54.dp),
        color = if (primary && enabled) ErpanColors.Blush else ErpanColors.Paper,
        shape = RoundedCornerShape(50), border = BorderStroke(0.8.dp, if (primary && enabled) ErpanColors.Rose else ErpanColors.Line)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            LineIcon(icon, tint, Modifier.size(21.dp)); Spacer(Modifier.width(9.dp))
            Text(label, fontSize = 17.sp, fontFamily = FontFamily.Serif, color = if (enabled) ErpanColors.Ink else tint)
        }
    }
}
