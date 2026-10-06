package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceFailureIsolationTest {
    @Test fun speechOnlyTimeoutIsReportedWhileTheTaskContinues() = runBlocking {
        val timedOut = CompletableDeferred<Unit>()
        val reports = mutableListOf<String>()
        var finished = false
        val flow = VoiceConversation(this, { "开始" }, { _, chunk ->
            chunk("第一步。")
            timedOut.await(); delay(20)
            chunk("第二步完成。")
            finished = true
        }, {
            try { withTimeout(10) { awaitCancellation() } }
            finally { timedOut.complete(Unit) }
        }, {}, reports::add)
        withTimeout(2000) { flow.submit(byteArrayOf(1)).join() }
        assertTrue(finished)
        assertTrue(reports.any { it.contains("朗读已停止") })
        assertTrue(reports.last().contains("朗读未完成"))
    }

    @Test fun speechFailureKeepsReadingTheSameReplyAndCaptionsWithoutRetry() = runBlocking {
        for (streaming in listOf(false, true)) {
            val speechAttempted = CompletableDeferred<Unit>()
            val captions = StringBuilder()
            val reports = mutableListOf<String>()
            var sends = 0
            var speechCalls = 0
            var finished = false
            val flow = VoiceConversation(this, { "开始讲解" }, { _, chunk ->
                sends++
                chunk("第一步。")
                speechAttempted.await()
                delay(30)
                repeat(100) { chunk("继续完成第${it}步。") }
                finished = true
            }, {
                speechCalls++; speechAttempted.complete(Unit)
                throw SpeechApiException("本机语音 暂时不可用。")
            }, {}, reports::add, streamSpeak = if (streaming) { input ->
                input.receive(); speechCalls++; speechAttempted.complete(Unit)
                throw SpeechApiException("本机语音 暂时不可用。")
            } else null, onReplyDelta = captions::append)
            val job = flow.submit(byteArrayOf(1))
            withTimeout(3000) { job.join() }
            assertTrue("speech failure must not cancel the model", finished)
            assertEquals(1, sends)
            assertEquals(1, speechCalls)
            assertTrue(captions.endsWith("继续完成第99步。"))
            assertTrue(reports.any { it.contains("朗读已停止") && it.contains("继续") })
            assertTrue(reports.last().contains("朗读"))
        }
    }

    @Test fun manualStopStillCancelsTaskAfterSpeechHasFailed() = runBlocking {
        val speechFailed = CompletableDeferred<Unit>()
        val sourceStopped = CompletableDeferred<Unit>()
        val flow = VoiceConversation(this, { "开始" }, { _, chunk ->
            try { chunk("第一步。"); awaitCancellation() }
            finally { sourceStopped.complete(Unit) }
        }, { throw SpeechApiException("音频失败。") }, {}, {
            if (it.contains("朗读已停止")) speechFailed.complete(Unit)
        })
        val job = flow.submit(byteArrayOf(1))
        try {
            withTimeout(1000) { speechFailed.await() }
            assertTrue(job.isActive)
            flow.interrupt()
            withTimeout(1000) { job.join(); sourceStopped.await() }
            assertTrue(job.isCancelled)
        } finally { job.cancelAndJoin() }
    }

    @Test fun aNewReplyCanSpeakNormallyAfterPreviousSpeechFailure() = runBlocking {
        val heard = mutableListOf<String>()
        var replies = 0
        val flow = VoiceConversation(this, { "继续" }, { _, chunk ->
            replies++; chunk("第${replies}条。")
        }, { text ->
            if (replies == 1) throw SpeechApiException("音频失败。")
            heard.add(text)
        }, {}, {})
        flow.submit(byteArrayOf(1)).join()
        flow.submit(byteArrayOf(2)).join()
        assertEquals(listOf("第2条。"), heard)
        assertEquals(2, replies)
    }
}
