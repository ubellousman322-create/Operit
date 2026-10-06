package com.huigu.phone10.mobile

/** Playback focus never owns capture, the conversation, or the overlay lifetime. */
internal class VoiceAudioFocus(private val request: () -> Boolean, private val pause: (Boolean) -> Unit,
                              private val coexist: Boolean = false) {
    private var closed = false
    private var held = false
    private var playing = false
    private var recoveryUsed = false

    @Synchronized fun beforePlayback() {
        check(!closed) { "通话已结束。" }
        if (playing) return
        playing = true
        recoveryUsed = false
        // User-selected mixing: capture/routing still belong to the call service,
        // but media focus is left with the game instead of creating a focus fight.
        if (coexist) { pause(false); return }
        if (!held) held = request()
        pause(!held)
    }

    @Synchronized fun lost() {
        if (closed || coexist) return
        held = false
        if (!playing) return
        pause(true)
        // One recovery attempt per reply; competing apps cannot create a retry loop.
        if (!recoveryUsed) {
            recoveryUsed = true
            held = request()
            if (held) pause(false)
        }
    }

    @Synchronized fun gained() {
        if (closed || coexist) return
        held = true
        if (playing) pause(false)
    }

    @Synchronized fun finishedPlayback() { playing = false; recoveryUsed = false }
    @Synchronized fun close() { closed = true; playing = false; held = false }
}
