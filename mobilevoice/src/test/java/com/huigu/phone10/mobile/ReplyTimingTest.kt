package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ReplyTimingTest {
    @Test fun identifiesTextAlreadyQueuedWhilePreviousPlaybackIsStillActive() = runBlocking {
        val snapshots = Channel<ReplySnapshot>(4)
        val events = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondPlayed = CompletableDeferred<Unit>()
        var plays = 0
        var clock = 0L
        val listener = ReplyListener({ after ->
            if (after == null) ReplySnapshot(10, emptyList(), false) else snapshots.receive()
        }, { chunks -> launch {
            val number = ++plays
            for (chunk in chunks) { /* consume without recording content */ }
            if (number == 1) releaseFirst.await() else secondPlayed.complete(Unit)
        } }, {}, {}, 1, events::add, { clock })
        val job = launch { listener.run() }
        snapshots.send(ReplySnapshot(11, listOf(ObservedReply(11, "PRIVATE_FIRST。")), false))
        withTimeout(2000) { while (plays != 1) delay(1) }
        snapshots.send(ReplySnapshot(12, listOf(ObservedReply(12, "PRIVATE_SECOND。")), false))
        withTimeout(2000) { while (events.none { it.startsWith("listen_enqueued_2_") }) delay(1) }
        assertFalse(events.any { it.startsWith("listen_dequeued_2_") })
        clock = 31_000
        releaseFirst.complete(Unit)
        withTimeout(2000) { secondPlayed.await() }
        job.cancelAndJoin()
        assertTrue(events.contains("listen_dequeued_2_wait_ms_31000"))
        assertTrue(events.contains("listen_baseline_processing_false"))
        assertFalse(events.any { it.contains("PRIVATE_") })
    }

    @Test fun marksFirstSpeakableTextAndSourceCloseBeforeSpeechFinishes() = runBlocking {
        val events = mutableListOf<String>()
        val chunks = Channel<String>(4)
        val releaseSpeech = CompletableDeferred<Unit>()
        val flow = VoiceConversation(this, { error("no recognition") }, { _, _ -> error("no model") },
            {}, {}, {}, streamSpeak = { input ->
                for (piece in input) { /* no content in diagnostics */ }
                releaseSpeech.await()
            }, diagnose = events::add)
        val job = flow.playReplyStream(chunks)
        chunks.send("<think>PRIVATE_REASON</think>PRIVATE_TEXT。"); chunks.close()
        withTimeout(2000) { while ("tts_source_complete" !in events) delay(1) }
        assertEquals(1, events.count { it.startsWith("tts_first_text_chars_") })
        assertFalse("tts_job_complete" in events)
        releaseSpeech.complete(Unit); job.join()
        assertTrue("tts_job_complete" in events)
        assertFalse(events.any { it.contains("PRIVATE_") })
    }
}
