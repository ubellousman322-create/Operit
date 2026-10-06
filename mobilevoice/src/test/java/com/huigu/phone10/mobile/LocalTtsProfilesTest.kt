package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class LocalTtsProfilesTest {
    @Test fun indexVoicesKeepDistinctIdsModesAndExistingSavedProfiles() {
        val cloud = SpeechConfig.bailianDefaults().copy(sttKey = "ASR", ttsKey = "CLOUD", voice = "cloud-voice")
        val legacy = cloud.withTtsProvider(SpeechConfig.QWEN_LOCAL).copy(
            ttsBaseUrl = "wss://voice.example.ts.net/v1/audio/speech/stream",
            ttsKey = "LOCAL", voice = "clone-1", qwenSplitGranularity = "none")
        val first = legacy.copy(ttsModel = "indextts2", voice = "index-reference-1")
        val second = first.copy(voice = "index-reference-2", qwenSplitGranularity = "sentence")
        assertTrue(first.validationErrors(false).isEmpty())
        assertTrue(second.validationErrors(false).isEmpty())
        val cloudSettings = MobileSettings(cloud, chatId = "existing-chat").saveVoiceProfile("云端")
        val saved = cloudSettings.copy(speech = legacy).saveVoiceProfile("原克隆1")
            .copy(speech = first).saveVoiceProfile("新声音一")
            .copy(speech = second).saveVoiceProfile("新声音二")
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(saved), MobileSettings::class.java)
        assertEquals(listOf(cloud, legacy, first, second), restored.profiles().map { it.speech })
        for (profile in restored.profiles()) {
            val selected = restored.selectVoiceProfile(profile.id)
            assertEquals(profile.speech, selected.speech)
            assertEquals("existing-chat", selected.chatId)
            assertEquals("ASR", selected.speech.sttKey)
        }
        assertTrue(first.wholeReplyTts)
        assertFalse(second.wholeReplyTts)
        assertTrue(first.copy(voice = "../voice.wav").validationErrors(false).isNotEmpty())
    }
}
