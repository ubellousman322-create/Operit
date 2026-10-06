package com.huigu.phone10.mobile

/** One call owns output selection; device changes do not restart capture or a reply. */
internal class VoiceAudioRoute(private val port: Port) : AutoCloseable {
    enum class Kind { WIRED, BLUETOOTH, MEDIA_BLUETOOTH, SPEAKER, OTHER }
    data class Device(val id: Int, val kind: Kind)
    interface Port {
        val modern: Boolean
        fun communicationDevices(): List<Device>
        fun outputDevices(): List<Device>
        fun speakerEnabled(): Boolean
        fun speaker(enabled: Boolean)
        fun select(id: Int): Boolean
        fun clear()
        fun sco(enabled: Boolean)
        fun watch(changed: () -> Unit): AutoCloseable
        fun record(event: String)
    }

    private var active = false
    private var watcher: AutoCloseable? = null
    private var previousSpeaker = false
    private var requestedId: Int? = null
    private var ownsSco = false

    fun start() {
        if (active) return
        previousSpeaker = port.speakerEnabled()
        active = true
        watcher = port.watch(::refresh)
        refresh()
    }

    private fun refresh() {
        if (!active) return
        runCatching {
            val outputs = port.outputDevices()
            if (port.modern) {
                val available = port.communicationDevices()
                val headset = available.firstOrNull { it.kind == Kind.WIRED }
                    ?: available.firstOrNull { it.id == requestedId && it.kind == Kind.BLUETOOTH }
                    ?: available.firstOrNull { it.kind == Kind.BLUETOOTH }
                val hasPersonalOutput = outputs.any { it.kind in setOf(Kind.WIRED, Kind.BLUETOOTH, Kind.MEDIA_BLUETOOTH) }
                val target = headset ?: if (!hasPersonalOutput)
                    available.firstOrNull { it.kind == Kind.SPEAKER } else null
                if (target == null) {
                    // Media presence alone is not a usable communication route.
                    // Release a prior speaker request while waiting for HFP/LE Audio.
                    port.clear(); requestedId = null; port.speaker(false)
                    port.record("audio_route_headset_unavailable")
                } else if (target.id != requestedId) {
                    if (port.select(target.id)) {
                        requestedId = target.id
                        port.record("audio_route_requested_${target.kind.name.lowercase()}")
                    } else {
                        port.clear(); requestedId = null; port.speaker(false)
                        port.record("audio_route_request_rejected")
                    }
                }
            } else {
                val wired = outputs.any { it.kind == Kind.WIRED }
                val bluetooth = !wired && outputs.any { it.kind in setOf(Kind.BLUETOOTH, Kind.MEDIA_BLUETOOTH) }
                // Disable speaker before initiating asynchronous SCO negotiation.
                port.speaker(!wired && !bluetooth)
                if (bluetooth && !ownsSco) {
                    port.sco(true); ownsSco = true
                    port.record("audio_route_sco_requested")
                } else if (!bluetooth && ownsSco) {
                    port.sco(false); ownsSco = false
                }
            }
        }.onFailure { port.record("audio_route_update_failed") }
    }

    override fun close() {
        if (!active) return
        active = false // queued callbacks cannot claim output after hangup
        runCatching { watcher?.close() }.onFailure { port.record("audio_route_unwatch_failed") }
        watcher = null
        if (port.modern) runCatching { port.clear() }.onFailure { port.record("audio_route_clear_failed") }
        else {
            if (ownsSco) runCatching { port.sco(false) }.onFailure { port.record("audio_route_sco_stop_failed") }
            runCatching { port.speaker(previousSpeaker) }.onFailure { port.record("audio_route_restore_failed") }
        }
        ownsSco = false; requestedId = null
    }
}
