package com.ai.assistance.operit.ui.features.chat.components.style.bubble

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一条语音条的配色。
 *
 * 这十套色值直接取自工具包 com.operit.voice_bar（侧栏里的「AI的电子嘴巴」）中的 THEMES 常量，
 * 目的是让 App 自己画的用户语音条，和插件画的 AI 语音条看上去出自同一套设计。
 */
data class VoiceNotePalette(
    val id: String,
    val label: String,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val playBg: Color,
    val playIcon: Color,
    val waveIdle: Color,
    val waveActive: Color,
    val durationColor: Color,
) {
    val backgroundBrush: Brush
        get() = Brush.linearGradient(listOf(bgTop, bgMid, bgBottom))

    val previewBrush: Brush
        get() = Brush.linearGradient(listOf(bgTop, bgBottom))
}

object VoiceNoteThemes {

    const val DEFAULT_ID = "MistyDream"

    val all: List<VoiceNotePalette> =
        listOf(
            VoiceNotePalette(
                id = "MistyDream",
                label = "朦胧梦境",
                bgTop = Color(0x94FFB6C1),
                bgMid = Color(0x85FFF5EE),
                bgBottom = Color(0x8FD4E1F1),
                playBg = Color(0x8CFFFFFF),
                playIcon = Color(0xFF8A5E6D),
                waveIdle = Color(0x99FFFFFF),
                waveActive = Color(0xFF9CAFC8),
                durationColor = Color(0xFF6D5A68),
            ),
            VoiceNotePalette(
                id = "FloatingLightLeaves",
                label = "浮光叶隙",
                bgTop = Color(0x94B1D5D8),
                bgMid = Color(0x85FEF8E3),
                bgBottom = Color(0x8FF7DA9A),
                playBg = Color(0x99FFFFFF),
                playIcon = Color(0xFF7F744F),
                waveIdle = Color(0x99FFFFFF),
                waveActive = Color(0xFFF7DA9A),
                durationColor = Color(0xFF79695F),
            ),
            VoiceNotePalette(
                id = "MintMambo",
                label = "薄荷曼波",
                bgTop = Color(0xADF0F0F0),
                bgMid = Color(0x85ADD8E6),
                bgBottom = Color(0xA3F5FFFA),
                playBg = Color(0x99FFFFFF),
                playIcon = Color(0xFF5E7F88),
                waveIdle = Color(0x99FFFFFF),
                waveActive = Color(0xFFADD8E6),
                durationColor = Color(0xFF526E74),
            ),
            VoiceNotePalette(
                id = "NightSea",
                label = "静夜海",
                bgTop = Color(0xF019192E),
                bgMid = Color(0xD1003E91),
                bgBottom = Color(0xE6002063),
                playBg = Color(0x1FFFFFFF),
                playIcon = Color(0xFFEAF0FF),
                waveIdle = Color(0x38FFFFFF),
                waveActive = Color(0xFFAFC8FF),
                durationColor = Color(0xFFDCE7FF),
            ),
            VoiceNotePalette(
                id = "icemist",
                label = "冰雾初融",
                bgTop = Color(0xD9F0F4FE),
                bgMid = Color(0xB3C3E1FA),
                bgBottom = Color(0xBFB2E1FC),
                playBg = Color(0xC7FFFFFF),
                playIcon = Color(0xFF3A6C9A),
                waveIdle = Color(0x737896B4),
                waveActive = Color(0xFF3A6C9A),
                durationColor = Color(0xFF2A5478),
            ),
            VoiceNotePalette(
                id = "purewhite",
                label = "极简纯白",
                bgTop = Color(0xF2FFFFFF),
                bgMid = Color(0xF2FAFAFA),
                bgBottom = Color(0xF2F8F8F8),
                playBg = Color(0x0F000000),
                playIcon = Color(0xFF222222),
                waveIdle = Color(0x2E000000),
                waveActive = Color(0xFF222222),
                durationColor = Color(0xFF444444),
            ),
            VoiceNotePalette(
                id = "wisteria",
                label = "紫藤轻染",
                bgTop = Color(0x9EB5ACC9),
                bgMid = Color(0x85EBCDDA),
                bgBottom = Color(0x9EFDE8ED),
                playBg = Color(0x94FFFFFF),
                playIcon = Color(0xFF5D5275),
                waveIdle = Color(0x9EFFFFFF),
                waveActive = Color(0xFF7A6A99),
                durationColor = Color(0xFF5D5275),
            ),
            VoiceNotePalette(
                id = "bamboo",
                label = "苍竹幽影",
                bgTop = Color(0x9E85A7A9),
                bgMid = Color(0x85C3D2B4),
                bgBottom = Color(0x9EFEF9D3),
                playBg = Color(0x94FFFFFF),
                playIcon = Color(0xFF3F6668),
                waveIdle = Color(0x9EFFFFFF),
                waveActive = Color(0xFF3F6668),
                durationColor = Color(0xFF315456),
            ),
            VoiceNotePalette(
                id = "dawnmist",
                label = "薄雾初晓",
                bgTop = Color(0x9E728B9A),
                bgMid = Color(0x80C8B9B2),
                bgBottom = Color(0x9EFCE5D7),
                playBg = Color(0x94FFFFFF),
                playIcon = Color(0xFF405869),
                waveIdle = Color(0x9EFFFFFF),
                waveActive = Color(0xFF5B7280),
                durationColor = Color(0xFF405869),
            ),
            VoiceNotePalette(
                id = "silentblack",
                label = "寂静纯黑",
                bgTop = Color(0xF21C1C1E),
                bgMid = Color(0xF2242428),
                bgBottom = Color(0xF228282C),
                playBg = Color(0x1AFFFFFF),
                playIcon = Color(0xFFF0F0F0),
                waveIdle = Color(0x38FFFFFF),
                waveActive = Color(0xFFF5F5F5),
                durationColor = Color(0xFFDDDDDD),
            ),
        )

    fun byId(id: String?): VoiceNotePalette =
        all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}

/** 语音条配色的本地存储。全局一份，不跟随角色卡。 */
object VoiceNoteThemeStore {

    private const val PREFS_NAME = "voice_note_style"
    private const val KEY_THEME_ID = "theme_id"

    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    fun attach(context: Context) {
        if (_current.value == null) {
            _current.value = read(context)
        }
    }

    fun read(context: Context): String =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, null)
            ?: VoiceNoteThemes.DEFAULT_ID

    fun set(context: Context, id: String) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_ID, id)
            .apply()
        _current.value = id
    }
}

/** 设置页里的一个配色格子：一个小圆点 + 名字。 */
@Composable
fun VoiceNoteThemeOption(
    palette: VoiceNotePalette,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val fillColor =
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else Color.Transparent
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
                .background(fillColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(18.dp)
                    .clip(CircleShape)
                    .background(palette.previewBrush)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = palette.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 「对话框」设置页里的「语音条配色」整块。 */
@Composable
fun VoiceNotePaletteSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceNoteThemeStore.attach(context) }
    val storedId by VoiceNoteThemeStore.current.collectAsState()
    val selectedId = storedId ?: VoiceNoteThemeStore.read(context)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(id = R.string.chat_style_voice_note_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        VoiceNoteThemes.all.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { palette ->
                    VoiceNoteThemeOption(
                        palette = palette,
                        selected = palette.id == selectedId,
                        modifier = Modifier.weight(1f),
                        onClick = { VoiceNoteThemeStore.set(context, palette.id) },
                    )
                }
                repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
