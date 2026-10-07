package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/**
 * 全屏通话页。夜与月那套底子，中间一张脸，名字和计时在下方，
 * 三个键固定在底部。
 */
@Composable
internal fun ErpanCallScreen(
    settings: MobileSettings,
    state: VoiceState,
    avatar: Drawable?,
    onEnd: () -> Unit,
    onMic: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // 服务还没给出开始时间（刚进这一页）就先按打开的时刻算，计时不会空着。
    val openedAt = remember { System.currentTimeMillis() }
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var speaker by remember { mutableStateOf(runCatching { audio.isSpeakerphoneOn }.getOrDefault(false)) }
    LaunchedEffect(Unit) { playCue(context, R.raw.call_connected) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500L)
            now = System.currentTimeMillis()
        }
    }
    val base = if (state.startedAt > 0L) state.startedAt else openedAt
    val seconds = ((now - base) / 1000L).toInt().coerceAtLeast(0)
    val speaking = state.micEnabled
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF19212B), Color(0xFF2C3644))))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(200.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    if (speaking) ErpanColors.Rose.copy(alpha = 0.34f)
                                    else Color(0xFF7A8EA4).copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )
                if (avatar != null) {
                    Image(
                        bitmap = avatar.toBitmap(200, 200).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(132.dp)
                            .clip(CircleShape)
                            .border(1.dp, ErpanColors.Rose.copy(alpha = 0.28f), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier
                            .size(132.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3A4858))
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(settings.displayChat(), color = Color(0xFFF1F5F9), fontSize = 26.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(10.dp))
            Text(
                if (seconds == 0) "正在通话…" else "%02d:%02d".format(seconds / 60, seconds % 60),
                color = Color(0xFF93A6BA),
                fontSize = 17.sp
            )
            Spacer(Modifier.height(36.dp))
            Text(
                state.caption.ifBlank { state.message },
                color = Color(0xFFB9C6D4),
                fontSize = 15.sp,
                lineHeight = 24.sp,
                maxLines = 4
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 44.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallKey("免提", !speaker, false) {
                    speaker = !speaker
                    runCatching { audio.isSpeakerphoneOn = speaker }
                }
                CallKey(if (state.micEnabled) "静音" else "已静音", !state.micEnabled, false, onMic)
                CallKey("挂断", false, true) {
                    runCatching { audio.isSpeakerphoneOn = false }
                    playCue(context, R.raw.call_hangup)
                    onEnd()
                }
            }
        }
    }
}

internal fun playCue(context: Context, resId: Int) {
    runCatching {
        MediaPlayer.create(context, resId)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}

@Composable
private fun CallKey(label: String, dim: Boolean, danger: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    when {
                        danger -> Color(0xFFE0564E)
                        dim -> Color.White.copy(alpha = 0.06f)
                        else -> Color.White.copy(alpha = 0.12f)
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(if (danger) "✕" else "◌", color = if (danger) Color.White else Color(0xFFD7E1EA), fontSize = 20.sp)
        }
        Text(label, color = if (danger) Color(0xFFE0564E) else Color(0xFF93A6BA), fontSize = 12.sp)
    }
}
