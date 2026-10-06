package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class CaptionInputFocusTest {
    @Test fun keyboardDismissalReleasesFocusWithoutSendingOrCancellingDraft() {
        val focus = CaptionInputFocus()
        assertTrue(focus.begin())
        assertTrue(focus.active)
        assertFalse(focus.keyboardVisible(false))
        assertFalse(focus.keyboardVisible(true))
        assertTrue(focus.keyboardVisible(false))
        assertTrue(focus.release())
        assertFalse(focus.active)
        assertFalse(focus.keyboardVisible(false))
    }

    @Test fun repeatedBeginAndReleaseDoNotToggleWindowFocus() {
        val focus = CaptionInputFocus()
        assertTrue(focus.begin())
        assertFalse(focus.begin())
        assertTrue(focus.release())
        assertFalse(focus.release())
        assertTrue(focus.begin())
        assertFalse(focus.keyboardVisible(false))
    }
}
