package com.huigu.phone10.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** Native AudioManager routing; shares the VoiceService communication mode owner. */
@Suppress("DEPRECATION")
internal class AndroidVoiceAudioRoute(private val context: Context, private val audio: AudioManager) : VoiceAudioRoute.Port {
    override val modern get() = Build.VERSION.SDK_INT >= 31
    private val handler = Handler(Looper.getMainLooper())

    private fun device(info: AudioDeviceInfo) = VoiceAudioRoute.Device(info.id, when (info.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> VoiceAudioRoute.Kind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> VoiceAudioRoute.Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> VoiceAudioRoute.Kind.MEDIA_BLUETOOTH
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> VoiceAudioRoute.Kind.SPEAKER
        else -> VoiceAudioRoute.Kind.OTHER
    })

    override fun communicationDevices() = if (Build.VERSION.SDK_INT >= 31)
        audio.availableCommunicationDevices.map(::device) else emptyList()
    override fun outputDevices() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::device)
    override fun speakerEnabled() = audio.isSpeakerphoneOn
    override fun speaker(enabled: Boolean) { audio.isSpeakerphoneOn = enabled }
    override fun select(id: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val target = audio.availableCommunicationDevices.firstOrNull { it.id == id } ?: return false
        return audio.setCommunicationDevice(target)
    }
    override fun clear() { if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() }
    override fun sco(enabled: Boolean) {
        if (enabled) {
            check(audio.isBluetoothScoAvailableOffCall)
            audio.startBluetoothSco()
            try { audio.isBluetoothScoOn = true }
            catch (error: Exception) { audio.stopBluetoothSco(); throw error }
        } else {
            try { audio.stopBluetoothSco() } finally { audio.isBluetoothScoOn = false }
        }
    }
    override fun record(event: String) = VoiceDiagnostics.record(event)

    override fun watch(changed: () -> Unit): AutoCloseable {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed()
        }
        audio.registerAudioDeviceCallback(callback, handler)
        var additional: AutoCloseable? = null
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // A selection request is asynchronous. Record the actual route
                // separately; callbacks never keep retrying against another app.
                val listener = AudioManager.OnCommunicationDeviceChangedListener {
                    record("audio_route_actual_${it?.let(::device)?.kind?.name?.lowercase() ?: "none"}")
                }
                audio.addOnCommunicationDeviceChangedListener(Executor { handler.post(it) }, listener)
                additional = AutoCloseable { audio.removeOnCommunicationDeviceChangedListener(listener) }
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                            record("audio_route_sco_state_$state")
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    null, handler, ContextCompat.RECEIVER_EXPORTED)
                additional = AutoCloseable { context.unregisterReceiver(receiver) }
            }
        } catch (error: Exception) {
            audio.unregisterAudioDeviceCallback(callback)
            throw error
        }
        return AutoCloseable {
            try { audio.unregisterAudioDeviceCallback(callback) } finally { additional?.close() }
        }
    }
}
