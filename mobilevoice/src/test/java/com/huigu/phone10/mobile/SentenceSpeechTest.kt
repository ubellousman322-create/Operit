package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SentenceSpeechTest {
    @Test fun failedPrefetchDoesNotCutOffTheSentenceAlreadyPlaying() = runBlocking {
        val input = Channel<String>(2).apply { trySend("current"); trySend("next"); close() }
        val nextFailed = CompletableDeferred<Unit>()
        val bytes = mutableListOf<Byte>()
        try {
            withTimeout(2000) {
                SentenceSpeech { text, rate, pcm ->
                    if (text == "next") {
                        nextFailed.complete(Unit)
                        throw java.io.IOException("prefetch unavailable")
                    }
                    rate(48000); pcm(byteArrayOf(1, 2)); pcm(byteArrayOf(3, 4))
                }.speak(input, {}) {
                    nextFailed.await()
                    // Model the current packet taking time to finish playing.
                    delay(30)
                    bytes.addAll(it.toList())
                }
            }
            fail("next sentence failure must be reported")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes)
    }

    @Test fun failureAfterFinalAudioPacketStillDeliversAlreadyReceivedTail() = runBlocking {
        val input = Channel<String>(1).apply { trySend("partial"); close() }
        val bytes = mutableListOf<Byte>()
        try {
            SentenceSpeech { _, rate, pcm ->
                rate(24000); pcm(byteArrayOf(1, 2)); pcm(byteArrayOf(3, 4))
                throw java.io.IOException("stream ended early")
            }.speak(input, {}) { delay(20); bytes.addAll(it.toList()) }
            fail("truncated stream must still be reported")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes)
    }

    @Test fun shortDeltasAndCommasStayTogetherUntilASpeakableClause() {
        var now = 0L
        val text = SpeechText(sentenceMode = true) { now }
        val parts = mutableListOf<String>()
        for (delta in listOf("嗯，", "我在", "这里", "陪你", "一起", "玩游戏。")) {
            parts += text.push(delta)
            now += 180
            parts += text.flushReady()
        }
        parts += text.flush()
        assertEquals(listOf("嗯，我在这里陪你一起玩游戏。"), parts)
    }

    @Test fun unfinishedSentenceEventuallySpeaksAndTinyTailIsNotLost() {
        var now = 0L
        val text = SpeechText(sentenceMode = true) { now }
        text.push("我们一起看看今天")
        now = 150
        assertTrue(text.flushReady().isEmpty())
        now = 1600
        assertEquals(listOf("我们一起看看今天"), text.flushReady())
        text.push("好")
        assertEquals(listOf("好"), text.flush())
    }

    @Test fun audioStartsBeforeSynthesisCompletes() = runBlocking {
        val input = Channel<String>(1).apply { trySend("第一句"); close() }
        val played = CompletableDeferred<Unit>()
        val bytes = mutableListOf<Byte>()
        withTimeout(2000) {
            SentenceSpeech { _, rate, pcm ->
                rate(48000); pcm(byteArrayOf(1, 2))
                played.await()
                pcm(byteArrayOf(3, 4))
            }.speak(input, {}, { bytes.addAll(it.toList()); played.complete(Unit) })
        }
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes)
    }

    @Test fun nextSentenceIsPreparedDuringPlaybackAndFormatIsConfiguredOnce() = runBlocking {
        val input = Channel<String>(3).apply {
            trySend("1"); trySend("2"); trySend("3"); close()
        }
        val secondReady = CompletableDeferred<Unit>()
        val rates = mutableListOf<Int>()
        val bytes = mutableListOf<Byte>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        withTimeout(2000) {
            SentenceSpeech { text, rate, pcm ->
                requests.add(text)
                rate(48000); pcm(byteArrayOf(text.toByte(), 0))
                if (text == "2") secondReady.complete(Unit)
            }.speak(input, rates::add) { pcm ->
                if (pcm[0] == 1.toByte()) {
                    secondReady.await()
                    assertEquals(listOf("1", "2"), requests.toList())
                }
                bytes.addAll(pcm.toList())
            }
        }
        assertEquals(listOf(48000), rates)
        assertEquals(listOf<Byte>(1, 0, 2, 0, 3, 0), bytes)
    }

    @Test fun interruptionCancelsPrefetchAndDoesNotLeakIntoNextReply() = runBlocking {
        val input = Channel<String>(2).apply { trySend("old1"); trySend("old2"); close() }
        val prefetched = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val bytes = mutableListOf<Byte>()
        val speech = SentenceSpeech { text, rate, pcm ->
            rate(24000)
            if (text == "old2") {
                prefetched.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            pcm(byteArrayOf(if (text == "new") 9 else 1, 0))
        }
        val turn = launch {
            speech.speak(input, {}) { prefetched.await(); awaitCancellation() }
        }
        withTimeout(2000) { prefetched.await(); turn.cancelAndJoin(); cancelled.await() }
        val next = Channel<String>(1).apply { trySend("new"); close() }
        speech.speak(next, {}) { bytes.addAll(it.toList()) }
        assertEquals(listOf<Byte>(9, 0), bytes)
    }

    @Test fun blockedAudioProducerIsBoundedAndCanBeCancelled() = runBlocking {
        val input = Channel<String>(1).apply { trySend("long"); close() }
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val allQueued = CompletableDeferred<Unit>()
        val turn = launch {
            SentenceSpeech { _, rate, pcm ->
                rate(48000)
                try {
                    pcm(ByteArray(4 * 1024 * 1024))
                    allQueued.complete(Unit)
                } finally { stopped.complete(Unit) }
            }.speak(input, {}) { started.complete(Unit); awaitCancellation() }
        }
        withTimeout(2000) { started.await() }
        assertFalse(allQueued.isCompleted)
        withTimeout(2000) { turn.cancelAndJoin(); stopped.await() }
    }

    @Test fun changedSampleRateFailsBeforeWrongSpeedAudioIsPlayed() = runBlocking {
        val input = Channel<String>(2).apply { trySend("1"); trySend("2"); close() }
        val bytes = mutableListOf<Byte>()
        try {
            SentenceSpeech { text, rate, pcm ->
                rate(if (text == "1") 24000 else 48000)
                pcm(byteArrayOf(text.toByte(), 0))
            }.speak(input, {}) { bytes.addAll(it.toList()) }
            fail("must reject format change")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf<Byte>(1, 0), bytes)
    }
}
