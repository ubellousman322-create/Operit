package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class VoiceAudioFocusTest {
    @Test fun sharedPlaybackContinuesWithoutRequestingGameFocus() {
        var requests = 0
        val pauses = mutableListOf<Boolean>()
        val focus = VoiceAudioFocus({ requests++; false }, pauses::add, coexist = true)
        focus.lost()
        focus.beforePlayback()
        repeat(5) { focus.lost(); focus.beforePlayback() }
        focus.finishedPlayback()
        focus.beforePlayback()
        assertEquals(0, requests)
        assertTrue(pauses.isNotEmpty())
        assertFalse(pauses.any { it })
        focus.close()
        val before = pauses.toList()
        focus.lost(); focus.gained()
        assertEquals(before, pauses)
        assertThrows(IllegalStateException::class.java) { focus.beforePlayback() }
    }

    @Test fun mutedGameTakingFocusBeforeSpeechDoesNotStartPlaybackOrReclaimEarly() {
        var requests = 0
        val focus = VoiceAudioFocus({ requests++; true }, {})
        focus.lost()
        assertEquals(0, requests)
        focus.beforePlayback()
        assertEquals(1, requests)
    }

    @Test fun playingRecoversOnceButNeverFightsRepeatedFocusLoss() {
        var requests = 0
        val pauses = mutableListOf<Boolean>()
        val focus = VoiceAudioFocus({ requests++; true }, pauses::add)
        focus.beforePlayback()
        focus.lost()
        assertEquals(2, requests)
        assertEquals(listOf(false, true, false), pauses)
        focus.lost()
        focus.beforePlayback()
        assertEquals(2, requests)
        assertTrue(pauses.last())
        focus.gained()
        assertFalse(pauses.last())
    }

    @Test fun deniedRecoveryWaitsForGainAndLateCallbacksCannotRestartAfterClose() {
        var requests = 0
        val pauses = mutableListOf<Boolean>()
        val focus = VoiceAudioFocus({ ++requests == 1 }, pauses::add)
        focus.beforePlayback(); focus.lost()
        assertTrue(pauses.last())
        focus.close(); val before = pauses.toList()
        focus.gained(); focus.lost()
        assertEquals(before, pauses)
        assertEquals(2, requests)
    }
}
