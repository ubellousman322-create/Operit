package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.cos
import kotlin.math.roundToInt

/** The original mic button remains the only floating touch target and accessibility button. */
class Phone10MicView(context: Context) : View(context) {
    var appearance: AvatarAppearance = AvatarAppearance()
        set(value) {
            val next = value.safe()
            if (field == next) return
            field = next
            updateShaders(); updateAnimation(); updateTicker(); invalidate()
        }
    var previewScale = 1f
        set(value) {
            if (field == value) return
            field = value
            updateShaders(); invalidate()
        }
    var avatar: Drawable? = null
        set(value) {
            (field as? Animatable)?.stop()
            field?.callback = null
            field = value
            value?.callback = this
            updateAnimation()
            invalidate()
        }
    var audioLevel = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - next) < 0.01f) return
            field = next
            invalidate()
        }
    private var microphone = Phone10MicrophoneState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val clipPath = Path()
    private var glowShader: RadialGradient? = null
    private var streamShader: SweepGradient? = null
    private var featherShader: RadialGradient? = null
    private val defaultAvatar = context.getDrawable(R.drawable.ic_mobile_voice)
    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            invalidate()
            postDelayed(this, 33)
        }
    }

    init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun render(next: Phone10MicrophoneState) {
        microphone = next
        contentDescription = when {
            next.pendingReview -> "语音待确认，点一下打开耳畔修改并发送，可拖动"
            next.changing -> "麦克风切换中，当前${if (next.enabled) "已开启" else "已关闭"}"
            next.error != null -> "切换失败，麦克风仍${if (next.enabled) "开启，点一下关闭" else "关闭，点一下开启"}"
            next.enabled -> "麦克风已开启，点一下关闭，可拖动"
            else -> "麦克风已关闭，点一下开启，可拖动"
        }
        updateAnimation()
        updateTicker()
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
    override fun performClick(): Boolean {
        if (microphone.changing && !microphone.pendingReview) return false
        return super.performClick()
    }
    override fun verifyDrawable(who: Drawable): Boolean = who === avatar || super.verifyDrawable(who)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation(); updateTicker() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaders()
    }
    override fun onDetachedFromWindow() {
        (avatar as? Animatable)?.stop()
        ticking = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
        updateTicker()
    }
    private fun updateAnimation() {
        val drawable = avatar as? Animatable ?: return
        if (shouldAnimateAvatar(isAttachedToWindow, isShown, microphone.enabled, appearance.animateAvatar))
            drawable.start() else drawable.stop()
    }
    private fun updateTicker() {
        val shouldTick = shouldTickAvatarEffect(isAttachedToWindow, isShown, microphone.enabled, appearance.glowMode)
        if (shouldTick == ticking) return
        ticking = shouldTick
        removeCallbacks(ticker)
        if (shouldTick) post(ticker)
    }
    private fun updateShaders() {
        if (width == 0 || height == 0) {
            glowShader = null
            streamShader = null
            featherShader = null
            return
        }
        val dp = resources.displayMetrics.density * previewScale
        val radius = appearance.avatarDp * dp / 2f * 0.875f
        val reach = appearance.glowRangeDp * dp
        val color = appearance.glowColor and 0x00ffffff
        val cx = width / 2f; val cy = height / 2f
        val total = radius + reach
        glowShader = when (appearance.glowMode) {
            "steady", "breath" -> RadialGradient(cx, cy, total,
                intArrayOf(color, color or 0xff000000.toInt(), color or 0x99000000.toInt(), color),
                floatArrayOf(0f, radius / total, (radius + reach * 0.45f) / total, 1f),
                Shader.TileMode.CLAMP)
            "mist" -> RadialGradient(cx, cy, total,
                intArrayOf(color, color or 0x22000000, color or 0x77000000,
                    color or 0x39000000, color),
                floatArrayOf(0f, (radius - minOf(4f * dp, radius * 0.1f)) / total,
                    radius / total, (radius + reach * 0.45f) / total, 1f),
                Shader.TileMode.CLAMP)
            else -> null
        }
        streamShader = if (appearance.glowMode == "stream") SweepGradient(cx, cy,
            intArrayOf(color, color, color or 0x55000000, color or 0xff000000.toInt(), color),
            floatArrayOf(0f, 0.58f, 0.76f, 0.9f, 1f)) else null
        featherShader = if (appearance.edgeFeather > 0f) RadialGradient(cx, cy, radius,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, appearance.featherStartFraction, 1f), Shader.TileMode.CLAMP) else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density * previewScale
        val cx = width / 2f
        val cy = height / 2f
        val radius = appearance.avatarDp * dp / 2f * 0.875f
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = 255
        if (microphone.enabled && glowShader != null) {
            val seconds = System.nanoTime() / 1_000_000_000.0
            val phase = when (appearance.glowMode) {
                "breath" -> 0.65f + 0.35f * cos(seconds * Math.PI * 2 * appearance.glowSpeed).toFloat()
                "mist" -> 0.78f + 0.22f * cos(seconds * Math.PI * 2 * appearance.glowSpeed * 0.13).toFloat()
                else -> 1f
            }
            val audio = if (appearance.audioReactive) 0.5f + 0.5f * audioLevel else 1f
            val alpha = (appearance.glowAlpha * phase * audio * 255).roundToInt().coerceIn(0, 255)
            paint.alpha = alpha
            paint.shader = glowShader
            canvas.drawCircle(cx, cy, radius + appearance.glowRangeDp * dp, paint)
            paint.shader = null
            paint.alpha = 255
        }
        if (!microphone.enabled) {
            paint.color = Color.argb(125, 65, 70, 78)
            canvas.drawRoundRect(cx - radius * 0.67f, cy - 3 * dp, cx + radius * 0.67f, cy + 3 * dp, 3 * dp, 3 * dp, paint)
            paint.color = Color.argb(95, 255, 255, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp
            canvas.drawRoundRect(cx - radius * 0.67f, cy - 3 * dp, cx + radius * 0.67f, cy + 3 * dp, 3 * dp, 3 * dp, paint)
        } else {
            val picture = avatar
            // DST_IN multiplies the source image's own alpha by the radial mask.
            // The layer stays inside the avatar circle, leaving the phone background visible.
            val feather = featherShader
            val layer = if (feather != null) canvas.saveLayer(cx - radius, cy - radius,
                cx + radius, cy + radius, null) else -1
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            if (picture != null) {
                val iw = picture.intrinsicWidth.coerceAtLeast(1)
                val ih = picture.intrinsicHeight.coerceAtLeast(1)
                val scale = radius * 2 / minOf(iw, ih) * appearance.imageScale
                val w = iw * scale; val h = ih * scale
                val x = cx + appearance.imageX * radius
                val y = cy + appearance.imageY * radius
                picture.setBounds((x - w / 2).roundToInt(), (y - h / 2).roundToInt(),
                    (x + w / 2).roundToInt(), (y + h / 2).roundToInt())
                picture.draw(canvas)
            } else {
                paint.color = Color.rgb(224, 228, 232)
                canvas.drawCircle(cx, cy, radius, paint)
                defaultAvatar?.setBounds((cx - radius).toInt(), (cy - radius).toInt(),
                    (cx + radius).toInt(), (cy + radius).toInt())
                defaultAvatar?.draw(canvas)
            }
            canvas.restore()
            if (feather != null) {
                maskPaint.shader = feather
                canvas.drawCircle(cx, cy, radius, maskPaint)
                maskPaint.shader = null
                canvas.restoreToCount(layer)
            }
            if (appearance.glowMode == "stream") {
                val audio = if (appearance.audioReactive) 0.5f + 0.5f * audioLevel else 1f
                val alpha = (appearance.glowAlpha * audio * 255).roundToInt().coerceIn(0, 255)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.55f * dp
                paint.color = appearance.glowColor
                paint.alpha = (alpha * 0.15f).roundToInt()
                canvas.drawCircle(cx, cy, radius + 2.2f * dp, paint)
                paint.alpha = alpha
                paint.shader = streamShader
                canvas.save()
                canvas.rotate(((System.nanoTime() / 1_000_000_000.0) * 42.0 * appearance.glowSpeed % 360.0).toFloat(), cx, cy)
                canvas.drawCircle(cx, cy, radius + 2.2f * dp, paint)
                canvas.restore()
                paint.shader = null
                paint.alpha = 255
            }
        }
        if (microphone.changing) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(117, 131, 148)
            canvas.drawCircle(cx, cy + radius * 0.72f, 2 * dp, paint)
        }
        if (microphone.error != null) {
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 75, 64)
            canvas.drawCircle(cx + radius * 0.76f, cy - radius * 0.72f, 3 * dp, paint)
        }
        if (microphone.pendingReview) {
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 75, 100)
            canvas.drawCircle(cx + radius * 0.72f, cy - radius * 0.72f, 8 * dp, paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            paint.textSize = 9 * dp; paint.isFakeBoldText = true
            canvas.drawText("改", cx + radius * 0.72f, cy - radius * 0.72f + 3 * dp, paint)
            paint.isFakeBoldText = false
        }
    }
}
