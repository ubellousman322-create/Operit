package com.huigu.phone10.mobile

import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable internal fun ErpanAppearance(
    value: AvatarAppearance,
    avatar: Drawable?,
    notice: String,
    busy: Boolean,
    onChange: (AvatarAppearance) -> Unit,
    onPick: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHeading("头像与光效", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(118.dp).background(ErpanColors.Blush, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center) {
                    AndroidView(factory = { context ->
                        Phone10MicView(context).apply {
                            render(Phone10MicrophoneState(enabled = true))
                            isClickable = false
                            isFocusable = false
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    }, modifier = Modifier.size(118.dp), update = { view ->
                        view.previewScale = minOf(2f, 110f / value.windowDp)
                        view.appearance = value
                        if (view.avatar !== avatar) view.avatar = avatar
                    })
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("悬浮头像预览", color = ErpanColors.Ink, fontSize = 16.sp)
                    OutlinedButton(onClick = onPick, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("更换图片") }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("播放动图", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = value.animateAvatar,
                            onCheckedChange = { onChange(value.copy(animateAvatar = it)) },
                            modifier = Modifier.semantics { contentDescription = "播放头像动画" })
                    }
                    TextButton(onClick = onReset, enabled = !busy) { Text("恢复默认") }
                }
            }
            Text("支持透明 PNG、JPEG、GIF 和动态 WebP；后者需 Android 9 及以上。",
                color = ErpanColors.Muted, fontSize = 12.sp)
            AppearanceSlider("头像大小", "${value.avatarDp} dp", value.avatarDp.toFloat(), 40f..160f,
                { onChange(value.copy(avatarDp = it.roundToInt())) })
            AppearanceSlider("边缘羽化", if (value.edgeFeather == 0f) "关闭" else "${(value.edgeFeather * 100).roundToInt()}%",
                value.edgeFeather, 0f..1f, { onChange(value.copy(edgeFeather = it)) })
            HorizontalDivider(color = ErpanColors.Line)
            Text("边缘效果", fontSize = 17.sp, color = ErpanColors.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "无", "stream" to "细流光", "mist" to "柔雾光").forEach { (mode, label) ->
                    FilterChip(selected = value.glowMode == mode, onClick = { onChange(value.copy(glowMode = mode)) },
                        label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("steady" to "经典常亮", "breath" to "经典呼吸").forEach { (mode, label) ->
                    FilterChip(selected = value.glowMode == mode, onClick = { onChange(value.copy(glowMode = mode)) },
                        label = { Text(label) })
                }
            }
            Text("羽化与边缘效果可单独使用，也可叠加。", color = ErpanColors.Muted, fontSize = 12.sp)
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (advanced) "收起裁剪与光效细节" else "展开裁剪与光效细节")
            }
            if (advanced) {
                AppearanceSlider("图片缩放", "${"%.1f".format(value.imageScale)} 倍", value.imageScale, 1f..3f,
                    { onChange(value.copy(imageScale = it)) })
                AppearanceSlider("图片横向位置", "${(value.imageX * 100).roundToInt()}%", value.imageX, -1f..1f,
                    { onChange(value.copy(imageX = it)) })
                AppearanceSlider("图片纵向位置", "${(value.imageY * 100).roundToInt()}%", value.imageY, -1f..1f,
                    { onChange(value.copy(imageY = it)) })
            }
            if (advanced && value.glowMode != "off") {
                Text("颜色", fontSize = 15.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "粉" to AndroidColor.rgb(244, 143, 175),
                        "蓝" to AndroidColor.rgb(116, 187, 255),
                        "紫" to AndroidColor.rgb(186, 145, 255),
                        "金" to AndroidColor.rgb(255, 197, 107),
                        "白" to AndroidColor.WHITE,
                    ).forEach { (name, color) ->
                        FilterChip(selected = value.glowColor == color,
                            onClick = { onChange(value.copy(glowColor = color)) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(11.dp).background(Color(color), CircleShape))
                                    Spacer(Modifier.width(4.dp)); Text(name)
                                }
                            })
                    }
                }
                val hsv = FloatArray(3).also { AndroidColor.colorToHSV(value.glowColor, it) }
                AppearanceSlider("色相", "${hsv[0].roundToInt()}°", hsv[0], 0f..360f,
                    { onChange(value.copy(glowColor = AndroidColor.HSVToColor(floatArrayOf(it, hsv[1], hsv[2])))) })
                AppearanceSlider("颜色浓度", "${(hsv[1] * 100).roundToInt()}%", hsv[1], 0f..1f,
                    { onChange(value.copy(glowColor = AndroidColor.HSVToColor(floatArrayOf(hsv[0], it, hsv[2])))) })
                AppearanceSlider("亮度 / 透明度", "${(value.glowAlpha * 100).roundToInt()}%",
                    value.glowAlpha, 0f..1f, { onChange(value.copy(glowAlpha = it)) })
                if (value.glowMode != "stream") AppearanceSlider("光效范围", "${value.glowRangeDp} dp",
                    value.glowRangeDp.toFloat(), 4f..48f,
                    { onChange(value.copy(glowRangeDp = it.roundToInt())) })
                if (value.glowMode in setOf("breath", "stream", "mist")) AppearanceSlider("变化速度",
                    "${"%.1f".format(value.glowSpeed)} 倍", value.glowSpeed, 0.3f..3f,
                    { onChange(value.copy(glowSpeed = it)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("随播放声音起伏")
                        Text("只读取耳畔正在播放的声音", color = ErpanColors.Muted, fontSize = 12.sp)
                    }
                    Switch(checked = value.audioReactive,
                        onCheckedChange = { onChange(value.copy(audioReactive = it)) },
                        modifier = Modifier.semantics { contentDescription = "随播放声音起伏" })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(color = ErpanColors.Paper, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                if (notice.isNotBlank()) Text(notice, color = ErpanColors.Rose, fontSize = 13.sp)
                Button(onClick = onSave, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存外观") }
            }
        }
    }
}

@Composable private fun AppearanceSlider(
    name: String, shown: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name); Text(shown, color = ErpanColors.Muted)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
        valueRange = range, modifier = Modifier.semantics { contentDescription = name })
}
