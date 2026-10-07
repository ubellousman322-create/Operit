package com.huigu.phone10.mobile

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 来电。与通话页同一套夜与月，但还没有接：
 * 名字在上面，底下两个圆 —— 左边接，右边挂。
 */
class IncomingCallActivity : ComponentActivity() {

    private var ring: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = runCatching { SettingsStore(this).load().allowIncoming }.getOrDefault(true)
        if (!allowed) {
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val who = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "姐姐"
        ring =
            runCatching {
                MediaPlayer.create(this, R.raw.call_ringback)?.apply {
                    isLooping = true
                    start()
                }
            }.getOrNull()
        setContent {
            ErpanTheme {
                IncomingCallScreen(
                    who = who,
                    onAccept = {
                        stopRing()
                        startService(
                            Intent(this@IncomingCallActivity, VoiceService::class.java)
                        )
                        // 接起来就直接进通话页 —— 不然人还得去通知栏里把它翻出来。
                        runCatching {
                            startActivity(
                                Intent(this@IncomingCallActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            )
                        }
                        finish()
                    },
                    onDecline = {
                        stopRing()
                        finish()
                    }
                )
            }
        }
    }

    private fun stopRing() {
        runCatching {
            ring?.stop()
            ring?.release()
        }
        ring = null
    }

    override fun onDestroy() {
        stopRing()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            runCatching {
                context.startActivity(
                    Intent(context, IncomingCallActivity::class.java)
                        .putExtra(EXTRA_NAME, name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }
    }
}

@Composable
private fun IncomingCallScreen(who: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF19212B), Color(0xFF2C3644))))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(150.dp))
            Text(who, color = Color(0xFFF1F5F9), fontSize = 38.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(12.dp))
            Text("来电", color = Color(0xFF93A6BA), fontSize = 16.sp, letterSpacing = 6.sp)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 96.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallCircle("挂断", Color(0xFFE0564E), onDecline)
                CallCircle("接听", Color(0xFF4FB07A), onAccept)
            }
        }
    }
}

@Composable
private fun CallCircle(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(if (label == "接听") "✓" else "✕", color = Color.White, fontSize = 24.sp)
        }
        Text(label, color = Color(0xFFB9C6D4), fontSize = 13.sp)
    }
}
