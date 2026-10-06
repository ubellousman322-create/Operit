package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VoiceProfilesTest {
    @Test fun editingSelectedVoiceKeepsItsIdentityForSaving() {
        val saved = settings().saveVoiceProfile("日常")
        val selected = saved.selectVoiceProfile(saved.profiles().single().id)
        val edited = selected.copy(speech = selected.speech.copy(voice = "edited-voice"))
        assertEquals("日常", edited.currentVoiceProfile()?.name)
    }

    @Test fun identicalConfigurationsStillSelectTheRequestedName() {
        val saved = settings().saveVoiceProfile("甲").saveVoiceProfile("乙")
        val selected = saved.selectVoiceProfile(saved.profiles().last().id)
        assertEquals("乙", selected.currentVoiceProfile()?.name)
    }

    @Test fun savingEditedProfileAppliesItAndKeepsOtherProfiles() {
        val initial = settings().saveVoiceProfile("甲")
        val both = initial.copy(speech = initial.speech.copy(voice = "voice-b")).saveVoiceProfile("乙")
        val originalB = both.profiles().last()
        val edited = both.selectVoiceProfile(initial.profiles().first().id)
            .copy(speech = initial.speech.copy(voice = "edited"), chatId = "chosen-chat")
        val committed = edited.saveVoiceProfile("甲", edited.currentVoiceProfile()!!.id)
        val restored = Gson().fromJson(Gson().toJson(committed), MobileSettings::class.java)
        assertEquals("edited", restored.speech.voice)
        assertEquals("edited", restored.currentVoiceProfile()?.speech?.voice)
        assertEquals("chosen-chat", restored.chatId)
        assertEquals(originalB, restored.profiles().last())
        assertEquals(2, restored.profiles().size)
    }

    @Test fun legacyUpgradeEnablesMixingWithoutChangingServicesOrChat() {
        val old = settings().saveVoiceProfile("旧方案").copy(gameAudioCoexist = false)
        val json = Gson().toJsonTree(old).asJsonObject.apply { remove("activeVoiceProfileId") }
        val upgraded = Gson().fromJson(json, MobileSettings::class.java).withCurrentVoiceDefaults()
        assertTrue(upgraded.gameAudioCoexist)
        assertEquals(old.speech, upgraded.speech)
        assertEquals(old.chatId, upgraded.chatId)
        assertEquals(old.profiles(), upgraded.profiles())
        assertEquals("旧方案", upgraded.copy(speech = upgraded.speech.copy(voice = "edit")).currentVoiceProfile()?.name)
    }

    private fun settings() = MobileSettings(SpeechConfig.openAiDefaults().copy(sttKey = "asr-a", ttsKey = "tts-a"),
        chatId = "original-chat", chatTitle = "原来的聊天", voiceName = "声音甲",
        overlayEnabled = true, gameAudioCoexist = true, disableVoiceInterruption = true)

    @Test fun switchingRestoresBothSpeechServicesWithoutChangingChatOrCallOptions() {
        val first = settings().saveVoiceProfile("方案甲")
        val second = first.copy(speech = first.speech.copy(ttsKey = "tts-b", voice = "voice-b"), voiceName = "声音乙")
            .saveVoiceProfile("方案乙")
        val restored = second.selectVoiceProfile(second.profiles().first().id)
        assertEquals(settings().speech, restored.speech)
        assertEquals("声音甲", restored.voiceName)
        assertEquals("original-chat", restored.chatId)
        assertTrue(restored.gameAudioCoexist)
        assertTrue(restored.overlayEnabled)
        assertTrue(restored.disableVoiceInterruption)
        assertEquals(2, restored.profiles().size)
    }

    @Test fun encryptedSettingsPayloadRoundTripPreservesProfilesAndLegacyStartsEmpty() {
        val gson = Gson()
        val saved = settings().saveVoiceProfile("日常")
        val restored = gson.fromJson(gson.toJson(saved), MobileSettings::class.java)
        assertEquals(saved, restored)
        val old = gson.toJsonTree(settings()).asJsonObject.apply { remove("voiceProfiles"); remove("captionsEnabled") }
        val legacy = gson.fromJson(old, MobileSettings::class.java)
        assertTrue(legacy.profiles().isEmpty())
        assertFalse(legacy.captionsEnabled)
        assertEquals(settings().speech, legacy.speech)
    }

    @Test fun updateIsExplicitAndRemoveKeepsTheCurrentUsableConfiguration() {
        val initial = settings().saveVoiceProfile("日常")
        val id = initial.profiles().single().id
        assertThrows(IllegalArgumentException::class.java) { initial.saveVoiceProfile("日常") }
        val edited = initial.copy(speech = initial.speech.copy(voice = "new-voice")).saveVoiceProfile("日常", id)
        assertEquals(1, edited.profiles().size)
        assertEquals(id, edited.profiles().single().id)
        assertEquals("new-voice", edited.selectVoiceProfile(id).speech.voice)
        val removed = edited.removeVoiceProfile(id)
        assertEquals(edited.speech, removed.speech)
        assertTrue(removed.profiles().isEmpty())
    }

    @Test fun invalidSpeechIsNotStoredAndSecretsAreNotInDisplayText() {
        assertThrows(IllegalArgumentException::class.java) { settings().copy(speech = SpeechConfig.bailianDefaults()).saveVoiceProfile("未填") }
        val profile = settings().saveVoiceProfile("日常").profiles().single()
        assertFalse(profile.toString().contains("tts-a"))
        assertFalse(profile.toString().contains("asr-a"))
    }
}
