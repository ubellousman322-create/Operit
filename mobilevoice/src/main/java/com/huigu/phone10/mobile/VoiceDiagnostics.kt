package com.huigu.phone10.mobile

/** Only bounded event labels and elapsed times. Never record speech, text, keys or addresses. */
object VoiceDiagnostics {
    private val events = ArrayDeque<String>()
    private var onsetAt = 0L
    /** 上一次「说完话 → 出第一声」的毫秒数，-1 表示还没量到。 */
    @Volatile var lastFirstAudioMillis: Long = -1L
        private set
    @Synchronized fun record(event: String) {
        val now = System.nanoTime() / 1_000_000
        events.addLast("$now ms · ${event.take(100)}")
        while (events.size > 120) events.removeFirst()
        if (event == "silence_segment_ready") onsetAt = now
        else if (event == "playback_first_pcm" && onsetAt > 0L) {
            lastFirstAudioMillis = now - onsetAt
            events.addLast("$now ms · first_audio_${lastFirstAudioMillis}ms")
            onsetAt = 0L
        }
    }
    @Synchronized fun snapshot(): String = events.joinToString("\n")
}

data class Phone10MicrophoneState(val enabled: Boolean = false, val changing: Boolean = false,
    val error: String? = null, val pendingReview: Boolean = false)
