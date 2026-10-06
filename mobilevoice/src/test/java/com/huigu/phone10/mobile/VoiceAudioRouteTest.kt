package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class VoiceAudioRouteTest {
    private val speaker = VoiceAudioRoute.Device(1, VoiceAudioRoute.Kind.SPEAKER)
    private val bluetooth = VoiceAudioRoute.Device(2, VoiceAudioRoute.Kind.BLUETOOTH)
    private val wired = VoiceAudioRoute.Device(3, VoiceAudioRoute.Kind.WIRED)
    private val media = VoiceAudioRoute.Device(4, VoiceAudioRoute.Kind.MEDIA_BLUETOOTH)

    @Test fun connectedBluetoothIsExplicitlySelectedInsteadOfLeavingSpeakerActive() {
        val port = Fake().apply { available = listOf(speaker, bluetooth); outputs = listOf(speaker, media) }
        val route = VoiceAudioRoute(port)
        route.start()
        assertEquals(listOf(2), port.selected)
        port.change(); assertEquals(listOf(2), port.selected)
        route.close(); assertEquals(1, port.clears)
    }

    @Test fun insertionAndRemovalSwitchOutputAndClosingUnregistersCallbacks() {
        val port = Fake().apply { available = listOf(speaker); outputs = available }
        val route = VoiceAudioRoute(port); route.start()
        port.available = listOf(speaker, bluetooth); port.outputs = port.available; port.change()
        port.available = listOf(speaker, bluetooth, wired); port.outputs = port.available; port.change()
        port.available = listOf(speaker, bluetooth); port.outputs = port.available; port.change()
        port.available = listOf(speaker); port.outputs = port.available; port.change()
        assertEquals(listOf(1, 2, 3, 2, 1), port.selected)
        val stale = port.callback
        route.close(); route.close(); stale?.invoke()
        assertEquals(1, port.unregistered)
        assertEquals(5, port.selected.size)
    }

    @Test fun mediaHeadsetAloneDoesNotPinTheCommunicationSpeaker() {
        val port = Fake().apply { available = listOf(speaker); outputs = listOf(speaker, media) }
        val route = VoiceAudioRoute(port); route.start()
        assertTrue(port.selected.isEmpty())
        assertFalse(port.speakerEnabled)
        assertTrue(port.events.contains("audio_route_headset_unavailable"))
        port.available = listOf(speaker, bluetooth); port.change()
        assertEquals(listOf(2), port.selected)
        route.close()
    }

    @Test fun rejectedHeadsetSelectionReleasesTheOldSpeakerRequest() {
        val port = Fake().apply { available = listOf(speaker); outputs = available }
        val route = VoiceAudioRoute(port); route.start()
        port.accept = false; port.available = listOf(speaker, bluetooth); port.outputs = port.available; port.change()
        assertEquals(1, port.clears)
        assertFalse(port.speakerEnabled)
        assertTrue(port.events.contains("audio_route_request_rejected"))
        route.close()
    }

    @Test fun legacyBluetoothStartsScoAndWiredInsertionStopsOnlyOwnedSco() {
        val port = Fake().apply { modern = false; outputs = listOf(speaker, media); speakerEnabled = false }
        val route = VoiceAudioRoute(port); route.start()
        assertEquals(listOf(true), port.scoRequests)
        assertFalse(port.speakerEnabled)
        port.change(); assertEquals(1, port.scoRequests.size)
        port.outputs = listOf(speaker, media, wired); port.change()
        assertEquals(listOf(true, false), port.scoRequests)
        port.outputs = listOf(speaker); port.change(); assertTrue(port.speakerEnabled)
        route.close(); assertFalse(port.speakerEnabled)
    }

    private class Fake : VoiceAudioRoute.Port {
        override var modern = true
        var available = emptyList<VoiceAudioRoute.Device>()
        var outputs = emptyList<VoiceAudioRoute.Device>()
        var speakerEnabled = true
        var callback: (() -> Unit)? = null
        var unregistered = 0; var clears = 0; var accept = true
        val selected = mutableListOf<Int>()
        val scoRequests = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        override fun communicationDevices() = available
        override fun outputDevices() = outputs
        override fun speakerEnabled() = speakerEnabled
        override fun speaker(enabled: Boolean) { speakerEnabled = enabled }
        override fun select(id: Int): Boolean { selected.add(id); return accept }
        override fun clear() { clears++ }
        override fun sco(enabled: Boolean) { scoRequests.add(enabled) }
        override fun watch(changed: () -> Unit): AutoCloseable {
            callback = changed
            return AutoCloseable { callback = null; unregistered++ }
        }
        override fun record(event: String) { events.add(event) }
        fun change() { callback?.invoke() }
    }
}
