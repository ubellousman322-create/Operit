package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/**
 * 全屏通话页：一整块被雨淋过的浅色玻璃，中间一张脸，名字和计时在下方，
 * 她那句话逐字浮在玻璃上，挂断键压在底部。
 *
 * 雨滴全在 [ErpanRainView] 里画，是原生 View；这一层只负责玻璃的厚度、
 * 人和字。两边分开之后，字怎么动都不影响雨，雨怎么变也不影响字。
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ErpanCallScreen(
    settings: MobileSettings,
    state: VoiceState,
    avatar: Drawable?,
    onEnd: () -> Unit,
    onMic: () -> Unit,
    onThemeChange: (String) -> Unit,
) {
    val palette = remember(settings.callTheme) { GlassPalette.of(settings.callTheme) }
    val ink = Color(palette.ink)
    val ink2 = Color(palette.ink2)
    val ink3 = Color(palette.ink3)
    val glow = Color(palette.glow)

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // 首声耗时不一定能从服务状态里及时传到这一层，直接盯诊断对象做兜底。
    var diagFirstMillis by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val measured = VoiceDiagnostics.lastFirstAudioMillis
            if (measured > 0) diagFirstMillis = measured
            delay(300)
        }
    }
    val openedAt = remember { System.currentTimeMillis() }
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    LaunchedEffect(Unit) { playCue(context, R.raw.call_connected) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500L)
            now = System.currentTimeMillis()
        }
    }
    // 头像位图只做一次，别每次重组都重新裁一遍。
    val face = remember(avatar) { avatar?.toBitmap(260, 260)?.asImageBitmap() }
    val base = if (state.startedAt > 0L) state.startedAt else openedAt
    val seconds = ((now - base) / 1000L).toInt().coerceAtLeast(0)

    // 她在想的时候，光晕跳得快一点，计时先藏起来——那几秒不是在通话，是在等。
    val thinking = state.changing || state.replying

    Box(Modifier.fillMaxSize()) {
        // 最底下那层玻璃和水珠。
        AndroidView(
            factory = { ctx -> ErpanRainView(ctx).also { it.palette = palette } },
            update = { it.palette = palette },
            modifier = Modifier.fillMaxSize()
        )

        GlassFinish()

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(62.dp))

            Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
                Halo(glow, thinking)
                Box(
                    Modifier
                        .size(184.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.04f))
                )
                if (face != null) {
                    Image(
                        bitmap = face,
                        contentDescription = null,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.55f))
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                CALL_NAME,
                color = ink,
                fontSize = 27.sp,
                letterSpacing = 4.3.sp,
                fontFamily = FontFamily.Serif
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (seconds == 0) "正在接通…" else "%02d:%02d".format(seconds / 60, seconds % 60),
                color = ink2.copy(alpha = if (thinking) 0f else 1f),
                fontSize = 15.sp,
                letterSpacing = 1.8.sp
            )
            Spacer(Modifier.height(7.dp))
            ThinkingDots(glow, thinking)

            Spacer(Modifier.weight(1f))

            // 两行，各管各的：我的转写只被我下一次开口替换，
            // 她的话只被她下一次回复替换，互不覆盖。
            val captionScroll = rememberScrollState()
            LaunchedEffect(state.myCaption, state.caption) {
                // 新的一句来了就贴到底。想往上回看可以划，但下一句一到会自己回来。
                captionScroll.animateScrollTo(captionScroll.maxValue)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(186.dp)
                    .verticalScroll(captionScroll),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.myCaption.isNotBlank()) {
                    FloatingLine(
                        text = state.myCaption,
                        color = ink2,
                        fontSize = 14.5.sp,
                        lineHeight = 24.sp
                    )
                    Spacer(Modifier.height(14.dp))
                }
                FloatingLine(
                    text = state.caption.ifBlank { state.message },
                    color = ink,
                    fontSize = 17.5.sp,
                    lineHeight = 31.5.sp
                )
            }

            Spacer(Modifier.height(30.dp))
            HangupKey(onClick = {
                runCatching { audio.isSpeakerphoneOn = false }
                playCue(context, R.raw.call_hangup)
                onEnd()
            })
            Spacer(Modifier.height(34.dp))
        }

        // 右上角三个配色，选中的那个记在设置里。
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassPalette.All.forEach { p ->
                val picked = p.key == palette.key
                Box(
                    Modifier
                        .size(21.dp)
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(p.g1), Color(p.g4))
                            )
                        )
                        .then(
                            if (picked) Modifier else Modifier.graphicsLayer { alpha = 0.55f }
                        )
                        .clickable { onThemeChange(p.key) }
                )
            }
        }
    }
}

/** 玻璃的厚度：上边缘亮、下边缘沉、四角收一圈光，最后压一层极淡的暗角。 */
@Composable
private fun GlassFinish() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 上沿到 18% 之间有一道白，像玻璃边缘受光；底部 24% 慢慢压暗。
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.40f),
                0.18f to Color.Transparent,
                0.76f to Color.Transparent,
                1f to Color(0xFF3C5064).copy(alpha = 0.10f)
            ),
            size = size
        )

        // 四周往里收一圈光。
        val ringWidth = 1f
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.30f)),
                center = Offset(w / 2f, h / 2f),
                radius = maxOf(w, h) * 0.72f
            ),
            size = size
        )
        drawRect(
            color = Color.White.copy(alpha = 0.55f),
            size = Size(w, ringWidth)
        )
    }
}

/** 头像后面那团会呼吸的光。 */
@Composable
private fun Halo(glow: Color, thinking: Boolean) {
    val transition = rememberInfiniteTransition(label = "halo")
    val scale by transition.animateFloat(
        initialValue = if (thinking) 0.98f else 0.90f,
        targetValue = if (thinking) 1.12f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (thinking) 1450 else 6400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )
    val alpha by transition.animateFloat(
        initialValue = if (thinking) 0.38f else 0.26f,
        targetValue = if (thinking) 0.62f else 0.46f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (thinking) 1450 else 6400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )
    Canvas(Modifier.size(184.dp)) {
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(glow.copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = size.minDimension * 0.68f
            ),
            radius = size.minDimension * 0.5f * scale
        )
    }
}

/** 三个点，她在想的时候才亮。 */
@Composable
private fun ThinkingDots(glow: Color, on: Boolean) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(
        Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1450, delayMillis = i * 240),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(glow.copy(alpha = if (on) a else 0f))
            )
        }
    }
}

/**
 * 一句话逐字浮上来。
 *
 * 不是打字机：整句的位置一开始就全部占满，每个字只是从下面一点、糊一点的地方
 * 回到原位。所以句子中途不会跳行，也不会把后面的字挤走。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    var revealed by remember { mutableStateOf(0) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        // 同一句在流式增长时接着往下浮。每来一个字就把整句重播一遍，
        // 会看着像在闪。
        val sameLine = text.startsWith(last) || last.startsWith(text)
        if (!sameLine) revealed = 0
        last = text
        while (revealed < text.length) {
            val ch = text[revealed]
            // 标点后面停得久一点，读起来才像话。
            delay(if (ch in "，。！？、；：…—") 190L else 70L)
            revealed += 1
        }
    }

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        text.forEachIndexed { i, ch ->
            CharCell(
                ch = if (ch == ' ') '\u00A0' else ch,
                on = i < revealed,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight
            )
        }
    }
}

@Composable
private fun CharCell(
    ch: Char,
    on: Boolean,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    val density = LocalDensity.current
    val progress by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            easing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
        ),
        label = "ch"
    )
    val rise = with(density) { 4.dp.toPx() }
    Text(
        text = ch.toString(),
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        textAlign = TextAlign.Center,
        fontFamily = FontFamily.Serif,
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * rise
        }
    )
}

@Composable
private fun HangupKey(onClick: () -> Unit) {
    Box(
        Modifier
            .size(63.dp)
            .clip(CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFFE2685F), Color(0xFFCF4A42))
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val s = size.minDimension
            val stroke = s * 0.11f
            drawLine(
                Color.White,
                Offset(s * 0.18f, s * 0.18f),
                Offset(s * 0.82f, s * 0.82f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                Color.White,
                Offset(s * 0.82f, s * 0.18f),
                Offset(s * 0.18f, s * 0.82f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

/** 通话页上那个名字。 */
private const val CALL_NAME = "姐姐"

internal fun playCue(context: Context, resId: Int) {
    runCatching {
        MediaPlayer.create(context, resId)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}