package com.huigu.phone10.mobile

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AvatarAppearanceTest {
    @Test fun hiddenAvatarDoesNotAnimateEvenWhileFloatingViewIsVisible() {
        assertFalse(shouldAnimateAvatar(attached = true, shown = true,
            micEnabled = false, animationEnabled = true))
        assertTrue(shouldAnimateAvatar(attached = true, shown = true,
            micEnabled = true, animationEnabled = true))
        assertFalse(shouldAnimateAvatar(attached = true, shown = false,
            micEnabled = true, animationEnabled = true))
    }

    @Test fun glowHasItsOwnWindowPadding() {
        val appearance = AvatarAppearance(avatarDp = 160, glowMode = "breath", glowRangeDp = 48)
        assertEquals(264, appearance.windowDp)
        assertEquals(160, appearance.copy(glowMode = "off").windowDp)
    }

    @Test fun thinStreamKeepsTouchWindowCloseToAvatarWhileMistHasRoomToFade() {
        assertEquals(172, AvatarAppearance(avatarDp = 160, glowMode = "stream", glowRangeDp = 48).safe().windowDp)
        assertEquals(264, AvatarAppearance(avatarDp = 160, glowMode = "mist", glowRangeDp = 48).safe().windowDp)
    }

    @Test fun savedValuesAreBoundedToOperableSizes() {
        val value = AvatarAppearance(avatarDp = 500, imageScale = 9f,
            imageX = -3f, glowMode = "unexpected", glowRangeDp = 100).safe()
        assertEquals(160, value.avatarDp)
        assertEquals(3f, value.imageScale, 0f)
        assertEquals(-1f, value.imageX, 0f)
        assertEquals("off", value.glowMode)
        assertEquals(48, value.glowRangeDp)
    }

    @Test fun featherKeepsTheCenterClearAndReachesTransparentAtTheEdge() {
        val full = AvatarAppearance(edgeFeather = 1f).safe()
        assertEquals(0.55f, full.featherStartFraction, 0.0001f)
        assertEquals(0f, AvatarAppearance(edgeFeather = -2f).safe().edgeFeather, 0f)
        assertEquals(1f, AvatarAppearance(edgeFeather = 9f).safe().edgeFeather, 0f)
    }

    @Test fun oldAppearancePreferencesKeepTheirGlowAndNewValuesRoundTrip() {
        val prefs = memoryPreferences(mutableMapOf("avatar_dp" to 66, "glow_mode" to "breath", "glow_range" to 31))
        val store = AvatarAppearanceStore(prefs)
        val old = store.load()
        assertEquals(66, old.avatarDp)
        assertEquals("breath", old.glowMode)
        assertEquals(31, old.glowRangeDp)
        assertEquals(0f, old.edgeFeather, 0f)
        store.save(old.copy(edgeFeather = 0.7f, glowMode = "mist"))
        assertEquals(0.7f, AvatarAppearanceStore(prefs).load().edgeFeather, 0f)
        assertEquals("mist", AvatarAppearanceStore(prefs).load().glowMode)
    }

    @Test fun edgeAnimationStopsWhenHiddenOrMicrophoneIsOff() {
        assertTrue(shouldTickAvatarEffect(true, true, true, "stream"))
        assertTrue(shouldTickAvatarEffect(true, true, true, "mist"))
        assertTrue(shouldTickAvatarEffect(true, true, true, "breath"))
        assertFalse(shouldTickAvatarEffect(true, false, true, "stream"))
        assertFalse(shouldTickAvatarEffect(true, true, false, "mist"))
        assertFalse(shouldTickAvatarEffect(true, true, true, "off"))
        assertFalse(shouldTickAvatarEffect(true, true, true, "steady"))
    }

    @Test fun audioReactiveBorderOnlyObservesLevelWhileVisibleAndMicrophoneIsOn() {
        assertTrue(shouldObserveAvatarAudioLevel(true, true, "stream", true))
        assertTrue(shouldObserveAvatarAudioLevel(true, true, "mist", true))
        assertFalse(shouldObserveAvatarAudioLevel(true, false, "stream", true))
        assertFalse(shouldObserveAvatarAudioLevel(false, true, "stream", true))
        assertFalse(shouldObserveAvatarAudioLevel(true, true, "off", true))
        assertFalse(shouldObserveAvatarAudioLevel(true, true, "breath", false))
    }
}

private fun memoryPreferences(values: MutableMap<String, Any>): SharedPreferences {
    val loader = AvatarAppearanceTest::class.java.classLoader
    val editor = Proxy.newProxyInstance(loader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putInt", "putFloat", "putBoolean", "putString" -> { values[args!![0] as String] = args[1]; proxy }
            "remove" -> { values.remove(args!![0] as String); proxy }
            "clear" -> { values.clear(); proxy }
            "commit" -> true
            "apply" -> null
            else -> error("Unexpected editor call: ${method.name}")
        }
    } as SharedPreferences.Editor
    return Proxy.newProxyInstance(loader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getInt", "getFloat", "getBoolean", "getString" -> values[args!![0] as String] ?: args[1]
            "edit" -> editor
            "contains" -> values.containsKey(args!![0] as String)
            "getAll" -> values.toMap()
            else -> error("Unexpected preferences call: ${method.name}")
        }
    } as SharedPreferences
}
