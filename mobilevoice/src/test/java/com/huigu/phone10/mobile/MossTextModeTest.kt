package com.huigu.phone10.mobile

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MossTextModeTest {
    @Test fun coherentKeepsLaterSentencesTogetherAndPreservesPunctuation() {
        val text = SpeechText(preserveSpacing = true, coherentMode = true)
        val first = "这是一段足够完整的开场介绍，我们今天一起慢慢读这个故事。"
        val later = "天色暗了。他停下来。她也没有说话。"
        assertEquals(listOf(first), text.push(first))
        assertTrue(text.push(later).isEmpty())
        assertEquals(listOf(later), text.flush())
    }

    @Test fun coherentHasBoundedWaitingWithoutDeletingPunctuation() {
        var now = 0L
        val text = SpeechText(coherentMode = true) { now }
        assertTrue(text.push("先说一句，接下来还会继续").isEmpty())
        now = 2499; assertTrue(text.flushReady().isEmpty())
        now = 2500; assertEquals(listOf("先说一句，接下来还会继续"), text.flushReady())
        text.push("工具运行时也要能念这一段。")
        now = 7499; assertTrue(text.flushReady().isEmpty())
        now = 7500; assertEquals(listOf("工具运行时也要能念这一段。"), text.flushReady())
    }

    @Test fun coherentLongUnpunctuatedTextKeepsEverySurrogatePair() {
        val original = "字".repeat(299) + "😀" + "尾".repeat(310)
        val text = SpeechText(coherentMode = true)
        val parts = original.flatMap { text.push(it.toString()) } + text.flush()
        assertEquals(original, parts.joinToString(""))
        assertTrue(parts.all { it.codePointCount(0, it.length) <= 300 })
        assertTrue(parts.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun coherentReducesRequestsForTheSameLongTextWithoutDroppingWords() {
        val original = "走过这条小路，我们在树下停一会儿。风吹过来，你继续说，我认真听。".repeat(20)
        val fast = SpeechText(sentenceMode = true)
        val coherent = SpeechText(coherentMode = true)
        val shortParts = fast.push(original) + fast.flush()
        val longerParts = coherent.push(original) + coherent.flush()
        assertEquals(original, longerParts.joinToString(""))
        assertTrue(longerParts.size < shortParts.size / 2)
    }

    @Test fun wholeHttpWaitsForActualReplyCompletionAndUsesOneCaptionSegment() = runBlocking {
        val sourceWaiting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        val captions = mutableListOf<String>()
        val flow = VoiceConversation(this, { "请求" }, { _, chunk ->
            chunk("第一句。第二句。")
            sourceWaiting.complete(Unit); finish.await()
            chunk("最后一句！")
        }, {}, {}, {}, streamSpeak = { input -> for (p in input) spoken.add(p) },
            sentenceTts = true, wholeReplyTts = true, onSpeechSegment = captions::add)
        val job = flow.submit(byteArrayOf(1))
        withTimeout(2000) { sourceWaiting.await() }
        delay(80)
        assertTrue("No synthesis during tool wait", spoken.isEmpty())
        finish.complete(Unit)
        withTimeout(2000) { job.join() }
        assertEquals(listOf("第一句。第二句。最后一句！"), spoken)
        assertEquals(spoken, captions)
    }

    @Test fun cancellingWholeReplyDiscardsCollectedText() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var requests = 0
        val flow = VoiceConversation(this, { "请求" }, { _, chunk ->
            chunk("这些文字已经到达，但是回复仍然没有结束。")
            waiting.complete(Unit); awaitCancellation()
        }, {}, {}, {}, streamSpeak = { input -> for (p in input) requests++ },
            sentenceTts = true, wholeReplyTts = true)
        val job = flow.submit(byteArrayOf(1))
        withTimeout(2000) { waiting.await() }
        flow.interrupt(); job.join()
        assertEquals(0, requests)
    }

    @Test fun legacySettingsStayFastAndModesPersistWithEachVoiceProfile() {
        val moss = SpeechConfig.openAiDefaults().withTtsProvider(SpeechConfig.MOSSLAND).copy(ttsKey="test", voice="test")
        val gson = Gson()
        val legacy = gson.toJsonTree(moss).asJsonObject.apply { remove("mossTextMode") }
        assertEquals("fast", gson.fromJson(legacy, SpeechConfig::class.java).effectiveMossTextMode)
        val whole = moss.copy(mossTextMode="whole")
        assertTrue(whole.wholeReplyTts)
        assertFalse(whole.withTtsProvider(SpeechConfig.OPENAI).wholeReplyTts)
        val settings = MobileSettings(whole, listenOnly=true).saveVoiceProfile("故事")
        val restored = gson.fromJson(gson.toJson(settings), MobileSettings::class.java)
        assertEquals("whole", restored.selectVoiceProfile(restored.profiles().single().id).speech.effectiveMossTextMode)
        assertTrue(moss.copy(mossTextMode="coherent").coherentTts)
        assertFalse(moss.copy(mossTextMode="coherent").withTtsProvider(SpeechConfig.QWEN_LOCAL).coherentTts)
        assertTrue(moss.copy(mossTextMode="unknown").validationErrors(false).any { it.contains("朗读方式") })
    }
}
