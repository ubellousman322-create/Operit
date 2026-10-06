package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class IndexLifecycleTest {
    private class Rig(@Volatile var hold: Boolean = false, val rejectFirst: Boolean = false) : AutoCloseable {
        val busy = AtomicBoolean(false)
        val restart = AtomicBoolean(false)
        val health = AtomicInteger()
        val releases = AtomicInteger()
        val activatedWhileBusy = AtomicBoolean(false)
        val openedWhileBusy = AtomicBoolean(false)
        val releasedWhileBusy = AtomicBoolean(false)
        val accepted = AtomicInteger()
        val attempts = AtomicInteger()
        val activations = AtomicInteger()
        val controls = MockWebServer()
        val audio = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        init {
            controls.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse = when (r.path) {
                    "/v1/audio/activate" -> {
                        activations.incrementAndGet()
                        if (busy.get()) activatedWhileBusy.set(true)
                        MockResponse().setBody("""{"lease_id":"lease-index-123456"}""")
                    }
                    "/v1/audio/release" -> {
                        releases.incrementAndGet()
                        if (busy.get()) releasedWhileBusy.set(true)
                        MockResponse().setBody("""{"released":true}""")
                    }
                    else -> {
                        health.incrementAndGet()
                        MockResponse().setBody("""{"state":"ready","low_latency_ready":true,"busy":${busy.get()},"restart_required":${restart.get()},"named_voices":{"index2-male-01":{"ready":true}}}""")
                    }
                }
            }
            audio.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    val n = attempts.incrementAndGet()
                    if (rejectFirst && n == 1) return MockResponse().setResponseCode(429)
                    if (busy.get()) openedWhileBusy.set(true)
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(ws: WebSocket, text: String) {
                            if (!text.contains("input.done")) return
                            accepted.incrementAndGet()
                            busy.set(true)
                            if (hold) return
                            ws.send("""{"type":"audio.start","utterance_index":0,"sentence_index":0,"format":"pcm","sample_rate":24000,"channels":1}""")
                            ws.send(byteArrayOf(1,2,3,4).toByteString())
                            ws.send("""{"type":"audio.done","utterance_index":0,"sentence_index":0,"total_bytes":4,"error":false}""")
                            ws.send("""{"type":"session.done","utterance_index":0,"total_sentences":1}""")
                        }
                    })
                }
            }
            controls.start(); audio.start()
        }
        val config get() = SpeechConfig.bailianDefaults().copy(ttsProvider=SpeechConfig.QWEN_LOCAL,
            ttsBaseUrl="wss://index.test/v1/audio/speech/stream",ttsKey="test-only",ttsModel="indextts2",
            voice="index2-male-01",qwenSplitGranularity="client_segments")
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(r: Request, l: WebSocketListener) =
                client.newWebSocket(r.newBuilder().url(audio.url("/speech")).build(),l)
        }
        fun speech(timeout: Long = 15000, status: suspend (String) -> Unit = {}) = QwenSpeech(config,factory,onStatus=status,
            lifecycle=QwenLifecycle(config,client,controls.url("/"),pollMillis=10,cleanupTimeoutMillis=timeout))
        fun input(vararg texts: String) = Channel<String>(texts.size).apply { texts.forEach { trySend(it) }; close() }
        override fun close() { client.dispatcher.cancelAll(); controls.shutdown(); audio.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun nextSegmentWaitsForServerCleanupAndKeepsLease() = runBlocking {
        Rig().use { r ->
            val job = async { r.speech().speakStream(r.input("第一，","第二。")) {} }
            try {
                withTimeout(3000) { while(r.accepted.get()<1) delay(5) }
                delay(150)
                assertEquals("next segment must wait for idle",1,r.attempts.get())
                assertEquals(0,r.releases.get())
                r.busy.set(false)
                withTimeout(3000) { while(r.accepted.get()<2) delay(5) }
                r.busy.set(false)
                withTimeout(3000) { job.await() }
                assertFalse(r.openedWhileBusy.get())
                assertEquals(1,r.releases.get())
            } finally { r.busy.set(false); job.cancelAndJoin() }
        }
    }

    @Test fun stopClearsPlayerImmediatelyButWaitsForIdleBeforeRelease() = runBlocking {
        Rig(hold=true).use { r ->
            val cleared = AtomicBoolean(false)
            val job=launch { r.speech().speakStream(r.input("停止测试。"),onAbort={cleared.set(true)}) {} }
            try {
                withTimeout(3000) { while(r.accepted.get()<1) delay(5) }
                job.cancel()
                withTimeout(1000) { while(!cleared.get()) delay(5) }
                delay(100)
                assertEquals("release cannot stand for GPU cancellation",0,r.releases.get())
                assertFalse(job.isCompleted)
                r.busy.set(false)
                withTimeout(3000) { job.join() }
                assertEquals(1,r.releases.get()); assertFalse(r.releasedWhileBusy.get())
            } finally { r.busy.set(false); job.cancelAndJoin() }
        }
    }

    @Test fun rejected429RetriesOnlyTheUnacceptedSegment() = runBlocking {
        Rig(rejectFirst=true).use { r ->
            val clear=launch { while(isActive) { if(r.accepted.get()>0) r.busy.set(false); delay(10) } }
            try {
                withTimeout(5000) { r.speech().speakStream(r.input("只合成一次。")) {} }
                assertEquals(2,r.attempts.get()); assertEquals(1,r.accepted.get())
            } finally { clear.cancelAndJoin(); r.busy.set(false) }
        }
    }

    @Test fun restartRequiredPreventsAnySynthesis() = runBlocking {
        Rig().use { r ->
            r.restart.set(true)
            val error=try { withTimeout(3000) { r.speech().speakStream(r.input("不应生成。")) {} }; null }
                catch(e: SpeechApiException) { e }
            assertNotNull(error); assertEquals(0,r.attempts.get())
        }
    }

    @Test fun unresolvedStopIsBoundedAndNextCallReconcilesOldLeaseFirst() = runBlocking {
        Rig(hold=true).use { r ->
            val statuses=java.util.concurrent.CopyOnWriteArrayList<String>()
            val job=launch { r.speech(150) { statuses.add(it) }.speakStream(r.input("旧请求。")) {} }
            withTimeout(3000) { while(r.accepted.get()<1) delay(5) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(statuses.any { it.contains("取消尚未确认") })
            assertEquals(0,r.releases.get())
            try { r.speech(150).speakStream(r.input("忙时不发送。")) {}; fail("must stop") }
            catch (_: SpeechApiException) { }
            assertEquals(1,r.attempts.get()); assertEquals(1,r.activations.get())
            r.busy.set(false); r.hold=false
            val clear=launch { while(isActive) { if(r.accepted.get()>1) r.busy.set(false); delay(5) } }
            try {
                withTimeout(3000) { r.speech().speakStream(r.input("新的请求。")) {} }
                assertEquals(2,r.activations.get()); assertEquals(2,r.releases.get())
            } finally { clear.cancelAndJoin() }
        }
    }

    @Test fun newCallCannotStartWhileOldCancellationIsStillBeingConfirmed() = runBlocking {
        Rig(hold=true).use { r ->
            val first=launch { r.speech().speakStream(r.input("旧请求。")) {} }
            withTimeout(3000) { while(r.accepted.get()<1) delay(5) }
            first.cancel()
            val next=launch { r.speech().speakStream(r.input("新请求。")) {} }
            try {
                delay(100)
                assertEquals(1,r.activations.get()); assertEquals(1,r.attempts.get())
                next.cancelAndJoin()
                r.busy.set(false)
                withTimeout(3000) { first.join() }
                assertEquals(1,r.releases.get())
            } finally { r.busy.set(false); first.cancelAndJoin(); next.cancelAndJoin() }
        }
    }
}
