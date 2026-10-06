package com.huigu.phone10.mobile

import android.content.Context
import android.content.SharedPreferences

data class AvatarAppearance(
    val avatarDp: Int = 48,
    val imageScale: Float = 1f,
    val imageX: Float = 0f,
    val imageY: Float = 0f,
    val edgeFeather: Float = 0f,
    val animateAvatar: Boolean = true,
    val glowMode: String = "off",
    val glowColor: Int = 0xFFF48FAF.toInt(),
    val glowAlpha: Float = 0.55f,
    val glowRangeDp: Int = 22,
    val glowSpeed: Float = 1f,
    val audioReactive: Boolean = false,
) {
    fun safe() = copy(
        avatarDp = avatarDp.coerceIn(40, 160),
        imageScale = imageScale.coerceIn(1f, 3f),
        imageX = imageX.coerceIn(-1f, 1f),
        imageY = imageY.coerceIn(-1f, 1f),
        edgeFeather = edgeFeather.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
        glowMode = glowMode.takeIf { it in setOf("off", "steady", "breath", "stream", "mist") } ?: "off",
        glowAlpha = glowAlpha.coerceIn(0f, 1f),
        glowRangeDp = glowRangeDp.coerceIn(4, 48),
        glowSpeed = glowSpeed.coerceIn(0.3f, 3f),
    )

    /** The center remains opaque; full feather starts at 55% of the image radius. */
    val featherStartFraction: Float get() = 1f - 0.45f * edgeFeather

    val windowDp: Int get() = avatarDp + when (glowMode) {
        "off" -> 0
        "stream" -> 12
        else -> (glowRangeDp + 4) * 2
    }
}

class AvatarAppearanceStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("avatar-appearance", Context.MODE_PRIVATE))

    fun load() = AvatarAppearance(
        avatarDp = prefs.getInt("avatar_dp", 48),
        imageScale = prefs.getFloat("image_scale", 1f),
        imageX = prefs.getFloat("image_x", 0f),
        imageY = prefs.getFloat("image_y", 0f),
        edgeFeather = prefs.getFloat("edge_feather", 0f),
        animateAvatar = prefs.getBoolean("animate_avatar", true),
        glowMode = prefs.getString("glow_mode", "off") ?: "off",
        glowColor = prefs.getInt("glow_color", AvatarAppearance().glowColor),
        glowAlpha = prefs.getFloat("glow_alpha", 0.55f),
        glowRangeDp = prefs.getInt("glow_range", 22),
        glowSpeed = prefs.getFloat("glow_speed", 1f),
        audioReactive = prefs.getBoolean("audio_reactive", false),
    ).safe()

    fun save(value: AvatarAppearance) {
        val v = value.safe()
        check(prefs.edit()
            .putInt("avatar_dp", v.avatarDp)
            .putFloat("image_scale", v.imageScale)
            .putFloat("image_x", v.imageX)
            .putFloat("image_y", v.imageY)
            .putFloat("edge_feather", v.edgeFeather)
            .putBoolean("animate_avatar", v.animateAvatar)
            .putString("glow_mode", v.glowMode)
            .putInt("glow_color", v.glowColor)
            .putFloat("glow_alpha", v.glowAlpha)
            .putInt("glow_range", v.glowRangeDp)
            .putFloat("glow_speed", v.glowSpeed)
            .putBoolean("audio_reactive", v.audioReactive)
            .commit()) { "外观设置保存失败。" }
    }
}

internal fun shouldAnimateAvatar(
    attached: Boolean,
    shown: Boolean,
    micEnabled: Boolean,
    animationEnabled: Boolean,
): Boolean = attached && shown && micEnabled && animationEnabled

internal fun shouldTickAvatarEffect(attached: Boolean, shown: Boolean, micEnabled: Boolean,
                                    glowMode: String): Boolean =
    attached && shown && micEnabled && (glowMode == "breath" || glowMode == "stream" || glowMode == "mist")

internal fun shouldObserveAvatarAudioLevel(visible: Boolean, micEnabled: Boolean,
                                           glowMode: String, audioReactive: Boolean): Boolean =
    visible && micEnabled && glowMode != "off" && audioReactive
