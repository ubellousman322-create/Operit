package com.huigu.phone10.mobile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class QwenProtocolTest {
    @Test fun realGatewayBooleanFalseCompletesButTrueAndMalformedErrorsFail() {
        val session = QwenPcmSession()
        session.event(start, false)
        session.pcm(byteArrayOf(1, 2))
        session.event(done.dropLast(1) + ",\"error\":false}", true)
        assertTrue(session.event(final, true))
        for (errorValue in listOf("true", "0", "\"false\"", "{}")) {
            val failed = QwenPcmSession()
            failed.event(start, false)
            failed.pcm(byteArrayOf(1, 2))
            assertThrows(SpeechApiException::class.java) {
                failed.event(done.dropLast(1) + ",\"error\":" + errorValue + "}", true)
            }
        }
    }

    @Test fun englishSentenceLeavesWhileInputStillOpenButDecimalsAndTitlesStayTogether() {
        val input = QwenTextInput()
        assertEquals(emptyList<String>(), input.push("Dr. "))
        assertEquals(emptyList<String>(), input.push("Lin counted 3.14"))
        assertEquals(listOf("Dr. Lin counted 3.14. \n"), input.push(". "))
        assertEquals(listOf("Hello world. \n"), input.push("Hello world. "))
        assertEquals(emptyList<String>(), input.finish())
    }

    @Test fun longUnpunctuatedFragmentsStayBoundedAndKeepSplitEmoji() {
        val input = QwenTextInput()
        val pieces = input.push("甲".repeat(4094) + "\uD83D") +
            input.push("\uDE00" + "乙".repeat(4300)) + input.finish()
        assertEquals("甲".repeat(4094) + "😀" + "乙".repeat(4300), pieces.joinToString(""))
        assertTrue(pieces.all { it.codePointCount(0, it.length) <= 4096 })
        assertTrue(pieces.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() || '\n' in it })
    }

    @Test fun explicitTextLimitFailsRatherThanSilentlyDroppingTail() {
        val input = QwenTextInput()
        val all = input.push("甲".repeat(20_000)) + input.finish()
        assertEquals("甲".repeat(20_000), all.joinToString(""))
        val tooLong = QwenTextInput()
        tooLong.push("甲".repeat(20_001))
        assertThrows(SpeechApiException::class.java) { tooLong.finish() }
    }

    @Test fun receivingPcmBeforeStartOrAfterSentenceEndsIsRejected() {
        val session = QwenPcmSession()
        assertThrows(IllegalArgumentException::class.java) { session.pcm(byteArrayOf(1, 2)) }
        session.event(start, false)
        session.pcm(byteArrayOf(1, 2))
        session.event(done, false)
        assertThrows(IllegalArgumentException::class.java) { session.pcm(byteArrayOf(3, 4)) }
    }

    @Test fun endCannotSucceedWhileInputOpenOrWithFractionalByteCount() {
        val session = QwenPcmSession()
        session.event(start, false); session.pcm(byteArrayOf(1, 2)); session.event(done, false)
        assertThrows(IllegalArgumentException::class.java) { session.event(final, false) }
        assertTrue(session.event(final, true))
        val broken = QwenPcmSession()
        broken.event(start, false); broken.pcm(byteArrayOf(1, 2))
        assertThrows(Exception::class.java) { broken.event(done.replace(":2}", ":2.5}"), false) }
    }

    @Test fun providerErrorAndStereoDoNotReachPcmConsumer() {
        val stereo = QwenPcmSession()
        assertThrows(IllegalArgumentException::class.java) { stereo.event(start.dropLast(1) + ",\"channels\":2}", false) }
        val error = QwenPcmSession()
        error.event(start, false); error.pcm(byteArrayOf(1, 2))
        assertThrows(SpeechApiException::class.java) { error.event(done.dropLast(1) + ",\"error\":\"private-body\"}", false) }
    }

    @Test fun qwenProfileRoundTripKeepsCloudProfileAndChatBinding() {
        val cloud = SpeechConfig.bailianDefaults().copy(sttKey = "STT", ttsKey = "CLOUD", voice = "old-cloud")
        val original = MobileSettings(cloud, chatId = "existing-chat", listenOnly = true).saveVoiceProfile("云端")
        val qwen = cloud.withTtsProvider(SpeechConfig.QWEN_LOCAL).copy(
            ttsBaseUrl = "wss://host.example.ts.net/v1/audio/speech/stream", ttsKey = "TTS")
        val both = original.copy(speech = qwen).saveVoiceProfile("本机")
        val restored = Gson().fromJson(Gson().toJson(both), MobileSettings::class.java)
        assertEquals("existing-chat", restored.chatId)
        assertEquals(cloud, restored.profiles().first().speech)
        assertEquals(qwen, restored.profiles().last().speech)
        assertEquals("本机语音 · 已保存音色", restored.profiles().last().description())
        assertEquals(cloud, restored.selectVoiceProfile(original.profiles().first().id).speech)
    }

    private val start = """{"type":"audio.start","utterance_index":0,"sentence_index":0,"format":"pcm","sample_rate":24000}"""
    private val done = """{"type":"audio.done","utterance_index":0,"sentence_index":0,"total_bytes":2}"""
    private val final = """{"type":"session.done","utterance_index":0,"total_sentences":1}"""
}
