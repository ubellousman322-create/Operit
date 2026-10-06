package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SpokenCaptionsTest {
    private class Sink : PcmSink {
        var head = 0L
        override fun playedFrames() = head
        override fun write(data: ByteArray, offset: Int, length: Int) = minOf(3, length)
        override fun close() = Unit
    }

    @Test fun captionsFollowPlayedFramesNotPrefetchedOrSubmittedAudio() {
        val sink = Sink()
        val player = PcmPlayer(sink)
        player.markCaptionSegment("first")
        player.write(ByteArray(20))
        player.markCaptionSegment("second")
        player.write(ByteArray(20))
        assertNull(player.playbackCaption())
        sink.head = 1
        assertEquals(0, player.playbackCaption())
        sink.head = 10
        assertEquals(0, player.playbackCaption())
        sink.head = 11
        assertEquals(1, player.playbackCaption())
        player.setPaused(true)
        assertEquals(1, player.playbackCaption())
        player.close()
        assertNull(player.playbackCaption())
    }

    @Test fun emptySegmentNeverClaimsPreviousAudioAndFreshPlayerStartsAtZero() {
        val sink = Sink()
        val player = PcmPlayer(sink)
        player.markCaptionSegment("one")
        player.write(ByteArray(8))
        player.markCaptionSegment("empty")
        sink.head = 4
        assertEquals(0, player.playbackCaption())
        player.markCaptionSegment("three")
        player.write(ByteArray(4))
        sink.head = 5
        assertEquals(2, player.playbackCaption())
        player.close()
        assertNull(PcmPlayer(Sink()).playbackCaption())
    }

    @Test fun cleanSegmentsHaveExactRangesIncludingRepeatedWordsAndSurrogates() {
        val captions = SpokenCaptions()
        captions.append("你好😀。")
        captions.append("你好😀。再见。")
        val range = captions.range(1)!!
        assertEquals("你好😀。再见。", captions.text.substring(range.first, range.last + 1))
        assertNull(captions.range(null))
        captions.clear()
        assertEquals("", captions.text)
        assertNull(captions.range(1))
    }

    @Test fun manualReadingPausesFollowUntilExplicitResumeOrNewReply() {
        val follow = CaptionFollow()
        assertTrue(follow.enabled)
        follow.userScrolled()
        assertFalse(follow.enabled)
        follow.resume()
        assertTrue(follow.enabled)
        follow.userScrolled()
        follow.reset()
        assertTrue(follow.enabled)
    }

    @Test fun segmentMarkersAreDeliveredInAudioOrderDespitePrefetch() = runBlocking {
        val input = Channel<String>(2).apply { trySend("first"); trySend("next"); close() }
        val prefetched = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        withTimeout(2000) {
            SentenceSpeech { text, rate, pcm ->
                if (text == "next") prefetched.complete(Unit)
                rate(24000); pcm(byteArrayOf(1, 0))
            }.speak(input, {}, onSegment = { events.add(it) }) {
                if (events.size == 1) { prefetched.await(); assertEquals(listOf("first"), events) }
                events.add("pcm")
            }
        }
        assertEquals(listOf("first", "pcm", "next", "pcm"), events)
    }

    @Test fun callAndListenCaptionsUseExactlyTheFilteredSpokenSegments() = runBlocking {
        for (listen in listOf(false, true)) {
            val captions = SpokenCaptions()
            val spoken = mutableListOf<String>()
            val text = "<think>不能朗读的推理。</think>**你好😀**。你好😀。再见。"
            val flow = VoiceConversation(this, { "请求" }, { _, chunk ->
                for (c in text) chunk(c.toString())
            }, {}, {}, {}, streamSpeak = { input ->
                for (part in input) {
                    val range = captions.range(spoken.size)!!
                    assertEquals(part, captions.text.substring(range.first, range.last + 1))
                    spoken.add(part)
                }
            }, firstClauseTts = true, onReplyStart = captions::clear, onSpeechSegment = captions::append)
            withTimeout(3000) {
                if (listen) flow.playReply(text).join() else flow.submit(byteArrayOf(1)).join()
            }
            assertEquals("你好😀。你好😀。再见。", spoken.joinToString(""))
            assertFalse(captions.text.contains("推理"))
            assertFalse(captions.text.contains("**"))
        }
    }

    @Test fun paddingCannotMakeAnEmptyFinalSegmentAppearSpoken() = runBlocking {
        var head = 0L
        val sink = object : PcmSink {
            override fun playedFrames() = head
            override fun write(data: ByteArray, offset: Int, length: Int): Int { head += length / 2; return length }
            override fun endPaddingBytes() = 100
            override fun close() = Unit
        }
        val player = PcmPlayer(sink)
        player.markCaptionSegment("actual")
        player.write(ByteArray(8))
        player.markCaptionSegment("empty")
        player.drain()
        assertEquals(0, player.playbackCaption())
        player.close()
    }
}
