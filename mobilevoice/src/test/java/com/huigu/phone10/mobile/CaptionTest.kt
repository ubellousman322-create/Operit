package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptionTest {
    @Test fun bufferRetainsOnlyCurrentReplyAndBoundsLongTextWithoutSplittingEmoji() {
        val buffer = CaptionBuffer(6)
        buffer.append("前文😀新的回复")
        assertTrue(buffer.text.length <= 6)
        assertFalse(buffer.text.first().isLowSurrogate())
        assertTrue(buffer.text.endsWith("新的回复"))
        buffer.clear(); buffer.append("下一轮")
        assertEquals("下一轮", buffer.text)
        buffer.clear(); assertEquals("", buffer.text)
    }

    @Test fun captionsArriveBeforeReplyCompletesAndMuteDoesNotStopThem() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val captions = mutableListOf<String>()
        var begins = 0
        val flow = VoiceConversation(this, { "你好" }, { _, chunk ->
            chunk("前半句"); first.complete(Unit); finish.await(); chunk("后半句。")
        }, {}, {}, {}, onReplyStart = { begins++ }, onReplyDelta = captions::add)
        val turn = flow.submit(byteArrayOf(1)); first.await()
        assertEquals(listOf("前半句"), captions)
        flow.setMicrophoneEnabled(false); finish.complete(Unit); turn.join()
        assertEquals(listOf("前半句", "后半句。"), captions)
        assertEquals(1, begins)
    }

    @Test fun cancelledTurnCannotDeliverLateTextIntoNextCaption() = runBlocking {
        val began = CompletableDeferred<Unit>()
        lateinit var oldChunk: suspend (String) -> Unit
        val captions = mutableListOf<String>()
        val flow = VoiceConversation(this, { "你好" }, { _, chunk -> oldChunk = chunk; began.complete(Unit); awaitCancellation() },
            {}, {}, {}, onReplyDelta = captions::add)
        val turn = flow.submit(byteArrayOf(1)); began.await(); flow.interrupt(); turn.join()
        try { oldChunk("旧回复"); fail("late callback accepted") } catch (_: CancellationException) { }
        assertTrue(captions.isEmpty())
    }

    @Test fun optionalCaptionFailureDoesNotCancelSpeech() = runBlocking {
        val spoken = mutableListOf<String>()
        val flow = VoiceConversation(this, { "你好" }, { _, chunk -> chunk("完整回复。") },
            spoken::add, {}, {}, onReplyStart = { error("view failed") }, onReplyDelta = { error("view failed") })
        flow.submit(byteArrayOf(1)).join()
        assertEquals(listOf("完整回复。"), spoken)
    }
}
