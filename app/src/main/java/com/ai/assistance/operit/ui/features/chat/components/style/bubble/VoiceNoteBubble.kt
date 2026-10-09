package com.ai.assistance.operit.ui.features.chat.components.style.bubble

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** 一条语音消息在正文里留下的痕迹：[voice:文件名|时长毫秒] */
data class VoiceNoteMark(val file: String, val durationMs: Long) {
    companion object {
        private val PATTERN = Regex("""\[voice:([^|\]]+)\|(\d+)]""")

        fun parse(text: String): VoiceNoteMark? =
            PATTERN.find(text)?.let {
                VoiceNoteMark(it.groupValues[1], it.groupValues[2].toLongOrNull() ?: 0L)
            }

        fun strip(text: String): String = PATTERN.replace(text, "").trim()
    }
}

@Composable
fun VoiceNoteBubble(mark: VoiceNoteMark, context: Context, modifier: Modifier = Modifier) {
    val file = remember(mark.file) {
        runCatching { File(File(context.filesDir, "voice-notes"), mark.file) }.getOrNull()
    }
    val peaks = remember(mark.file) { runCatching { readPeaks(file) }.getOrDefault(emptyList()) }
    var playing by remember(mark.file) { mutableStateOf(false) }
    val player = remember(mark.file) { arrayOfNulls<MediaPlayer>(1) }
    LaunchedEffect(Unit) { VoiceNoteThemeStore.attach(context) }
    val themeId by VoiceNoteThemeStore.current.collectAsState()
    val palette = remember(themeId) { VoiceNoteThemes.byId(themeId) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.backgroundBrush)
                .clickable {
                    val current = player[0]
                    if (playing) {
                        runCatching { current?.stop(); current?.release() }
                        player[0] = null
                        playing = false
                        return@clickable
                    }
                    runCatching {
                        val fresh = MediaPlayer()
                        fresh.setDataSource(file?.absolutePath)
                        fresh.setOnCompletionListener {
                            playing = false
                            runCatching { it.release() }
                            player[0] = null
                        }
                        fresh.prepare()
                        fresh.start()
                        player[0] = fresh
                        playing = true
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(palette.playBg),
            contentAlignment = Alignment.Center
        ) {
            Text(if (playing) "❚❚" else "▶", color = palette.playIcon, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val bars = if (peaks.isEmpty()) List(48) { 0.25f } else peaks
                bars.forEach { value ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height((22f * value.coerceIn(0.12f, 1f)).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (playing) palette.waveActive else palette.waveIdle)
                    )
                }
            }
            Text(
                text = formatDuration(mark.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = palette.durationColor
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** 从 wav 的 PCM 里取 48 个峰值，气泡上那根波形就是它。 */
private fun readPeaks(file: File?): List<Float> {
    if (file == null || !file.exists()) return emptyList()
    val bytes = file.readBytes()
    if (bytes.size <= 44) return emptyList()
    val body = bytes.size - 44
    val count = 48
    val step = (body / count).coerceAtLeast(2)
    val out = ArrayList<Float>(count)
    var offset = 44
    while (offset + step <= bytes.size && out.size < count) {
        var peak = 0
        var index = offset
        while (index + 1 < offset + step && index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xff
            val high = bytes[index + 1].toInt()
            val sample = kotlin.math.abs((high shl 8) or low)
            if (sample > peak) peak = sample
            index += 2
        }
        out.add(peak / 32768f)
        offset += step
    }
    val max = out.maxOrNull() ?: 0f
    return if (max <= 0.0001f) out else out.map { (it / max).coerceIn(0.12f, 1f) }
}
