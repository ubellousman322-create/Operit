package com.huigu.phone10.mobile

/** Tracks the temporary focus granted to the existing caption window for editing. */
internal class CaptionInputFocus {
    var active: Boolean = false
        private set
    private var sawKeyboard = false

    fun begin(): Boolean {
        if (active) return false
        active = true
        sawKeyboard = false
        return true
    }

    fun keyboardVisible(visible: Boolean): Boolean {
        if (!active) return false
        if (visible) sawKeyboard = true
        return sawKeyboard && !visible
    }

    fun release(): Boolean {
        if (!active) return false
        active = false
        sawKeyboard = false
        return true
    }
}
