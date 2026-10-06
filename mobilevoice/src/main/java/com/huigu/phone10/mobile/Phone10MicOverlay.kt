package com.huigu.phone10.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** View only: reuses the existing floating window and never owns a player or capture path. */
class Phone10MicOverlay(context: Context, private val onToggle: () -> Unit,
                        private val onVisible: (Boolean) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val avatars = Phone10AvatarStore(app)
    private val appearanceStore = AvatarAppearanceStore(app)
    private val loader = Phone10AvatarLoader(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var button: Phone10MicView? = null
    private var params: WindowManager.LayoutParams? = null
    private var size = 0
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateScreenVisibility() }
    }
    private val lifecycle = Phone10OverlayLifecycle { visible ->
        if (!visible) {
            loadJob?.cancel()
            button?.avatar = null
            button = null
            params = null
            unregisterScreenReceiver()
        }
        onVisible(visible)
    }
    private var state = Phone10MicrophoneState()
    private val density get() = app.resources.displayMetrics.density

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(app)) { hide(); return false }
        if (button != null && lifecycle.visible) { refreshAppearance(); return true }
        val appearance = appearanceStore.load()
        size = (appearance.windowDp * density).roundToInt()
        val layout = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (app.resources.displayMetrics.widthPixels - size - 12 * density).roundToInt()
            y = (180 * density).roundToInt()
        }
        val view = Phone10MicView(app).apply {
            this.appearance = appearance
            setOnClickListener { onToggle() }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (button === v) lifecycle.detached()
            }
        })
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        val gesture = Phone10OverlayGesture(ViewConfiguration.get(app).scaledTouchSlop.toFloat())
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = layout.x; startY = layout.y
                    gesture.down(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesture.move(event.rawX, event.rawY)) {
                        layout.x = (startX + event.rawX - downX).roundToInt().coerceIn(
                            0, (app.resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        layout.y = (startY + event.rawY - downY).roundToInt().coerceIn(
                            0, (app.resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        lifecycle.move { manager.updateViewLayout(view, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (gesture.up(event.rawX, event.rawY)) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { gesture.cancel(); true }
                else -> false
            }
        }
        try {
            manager.addView(view, layout)
        } catch (_: SecurityException) { return false }
        catch (_: WindowManager.BadTokenException) { return false }
        button = view
        params = layout
        lifecycle.attached { manager.removeView(view) }
        registerScreenReceiver()
        updateScreenVisibility()
        refreshAvatar()
        update(state)
        return true
    }

    fun update(next: Phone10MicrophoneState) {
        val freshError = next.error != null && next.error != state.error
        state = next
        if (!Settings.canDrawOverlays(app)) { hide(); return }
        button?.render(next)
        if (freshError && lifecycle.visible) {
            Toast.makeText(app, "切换失败，麦克风仍${if (next.enabled) "开启" else "关闭"}，可再点一次", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshAppearance() {
        val view = button ?: return
        val layout = params ?: return
        val next = appearanceStore.load()
        view.appearance = next
        val newSize = (next.windowDp * density).roundToInt()
        if (newSize != size) {
            layout.x = (layout.x + (size - newSize) / 2).coerceIn(
                0, (app.resources.displayMetrics.widthPixels - newSize).coerceAtLeast(0))
            layout.y = (layout.y + (size - newSize) / 2).coerceIn(
                0, (app.resources.displayMetrics.heightPixels - newSize).coerceAtLeast(0))
            size = newSize
            layout.width = newSize; layout.height = newSize
            lifecycle.move { manager.updateViewLayout(view, layout) }
        }
    }

    fun refreshAvatar() {
        val view = button ?: return
        loadJob?.cancel()
        view.avatar = null
        loadJob = scope.launch {
            try {
                val source = avatars.source()
                val drawable = withContext(Dispatchers.IO) { loader.load(source) }
                if (button === view) view.avatar = drawable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (button === view) Toast.makeText(app, "头像无法显示，请在外观里重新选择图片。", Toast.LENGTH_LONG).show()
            }
        }
    }

    val needsAudioLevel: Boolean get() {
        val view = button ?: return false
        return shouldObserveAvatarAudioLevel(lifecycle.visible && view.visibility == View.VISIBLE,
            state.enabled, view.appearance.glowMode, view.appearance.audioReactive)
    }
    fun setAudioLevel(level: Float) { button?.audioLevel = level }
    fun reposition() {
        val view = button ?: return
        val layout = params ?: return
        layout.x = layout.x.coerceIn(0, (app.resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (app.resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
        lifecycle.move { manager.updateViewLayout(view, layout) }
    }
    fun hide() {
        loadJob?.cancel()
        lifecycle.hide()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(screenReceiver, filter)
        receiverRegistered = true
    }
    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { app.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }
    private fun updateScreenVisibility() {
        val interactive = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        button?.visibility = if (interactive && !locked) View.VISIBLE else View.INVISIBLE
    }
}
