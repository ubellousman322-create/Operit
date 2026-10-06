package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReplyPollTraceTest {
    @Test fun separatesSchedulerGapFromSlowReadWithoutLoggingMessageContent() = runBlocking {
        val events = mutableListOf<String>()
        val baselineReady = CompletableDeferred<Unit>()
        val readDone = CompletableDeferred<Unit>()
        var clock = 0L
        val listener = ReplyListener({ after ->
            if (after == null) ReplySnapshot(10, emptyList(), false) else {
                clock += 1700
                readDone.complete(Unit)
                ReplySnapshot(10, emptyList(), false)
            }
        }, { error("no playback") }, {}, { baselineReady.complete(Unit) },
            30, events::add, { clock })
        val job = launch { listener.run() }
        baselineReady.await()
        clock = 4000
        readDone.await()
        job.cancelAndJoin()
        assertTrue(events.contains("listen_poll_gap_ms_4000"))
        assertTrue(events.contains("listen_slow_read_ms_1700"))
        assertTrue(events.contains("listen_poll_begin_1"))
        assertTrue(events.contains("listen_poll_end_1_replies_0_processing_false_advanced_false"))
    }

    @Test fun boundsIdlePollDiagnosticsButProvesReadsContinue() = runBlocking {
        val events = mutableListOf<String>()
        val completed = CompletableDeferred<Unit>()
        var reads = 0
        var clock = 0L
        val listener = ReplyListener({ after ->
            if (after != null) {
                reads++
                clock += 500
                if (reads == 22) completed.complete(Unit)
            }
            ReplySnapshot(10, emptyList(), false)
        }, { error("no playback") }, {}, {}, 1, events::add, { clock })
        val job = launch { listener.run() }
        completed.await()
        job.cancelAndJoin()
        assertEquals(2, events.count { it.startsWith("listen_poll_begin_") })
        assertEquals(2, events.count { it.startsWith("listen_poll_end_") })
        assertTrue(events.contains("listen_poll_begin_21"))
        assertFalse(events.any { it.startsWith("listen_poll_gap_") })
    }
}
