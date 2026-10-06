package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class QwenSpeechTest {
    @Test fun indexNonePreservesTextAndSurrogatesAcrossInputFragments() = runBlocking {
        Fixture().use { f ->
            val selected=wholeConfig("index2-male-mix-02", "none").copy(ttsModel="indextts2")
            val input=Channel<String>(3).apply { trySend("第一句。\uD83D"); trySend("\uDE00"); trySend("下一句。"); close() }
            withTimeout(3000) { QwenSpeech(selected,f.factory).speakStream(input) {} }
            val text=f.messages.map { JsonParser.parseString(it).asJsonObject }
                .filter { it["type"].asString=="input.text" }.joinToString("") { it["text"].asString }
            assertEquals("第一句。😀下一句。",text)
        }
    }
    @Test fun indexRequestLimitIs600CodePointsAndPreservesExactUnicode() = runBlocking {
        for (count in listOf(600, 601)) Fixture().use { f ->
            val selected = wholeConfig("index2-male-mix-02", "client_segments").copy(ttsModel="indextts2")
            val body = "😀".repeat(count)
            val input = Channel<String>(1).apply { trySend(body); close() }
            val failure = runCatching { withTimeout(3000) {
                QwenSpeech(selected,f.factory).speakStream(input) {}
            } }.exceptionOrNull()
            if (count==600) {
                assertNull(failure)
                val text=f.messages.map { JsonParser.parseString(it).asJsonObject }
                    .single { it["type"].asString=="input.text" }["text"].asString
                assertEquals(body,text)
            } else {
                assertTrue(failure is SpeechApiException)
                assertTrue(f.requests.isEmpty())
            }
        }
    }
    @Test fun firstClauseFlowsThroughCallAndListenOnlyBeforeTextCompletes() = runBlocking {
        for (listen in listOf(false, true)) Fixture().use { f ->
            val selected = wholeConfig("index2-male-01", "client_segments").copy(ttsModel = "indextts2")
            val firstAudio = CompletableDeferred<Unit>()
            val states = mutableListOf<String>()
            val chunks = Channel<String>(2)
            val flow = VoiceConversation(this, { "读给我听" }, { _, delta ->
                delta("小盈，")
                withTimeout(3000) { firstAudio.await() }
                delta("后面的话。")
            }, { fail("second player") }, {}, { states.add(it) }, streamSpeak = { input ->
                CloudSpeech(selected, f.client, f.factory).speakStream(input) { firstAudio.complete(Unit) }
            }, firstClauseTts = true)
            val job = if (listen) flow.playReplyStream(chunks) else flow.submit(byteArrayOf(1))
            try {
                if (listen) {
                    chunks.send("小盈，")
                    withTimeout(3000) { firstAudio.await() }
                    assertTrue(job.isActive)
                    chunks.send("后面的话。")
                    chunks.close()
                }
                withTimeout(5000) { job.join() }
                assertEquals(2, f.requests.size)
                val texts = f.messages.map { JsonParser.parseString(it).asJsonObject }
                    .filter { it["type"].asString == "input.text" }.map { it["text"].asString }
                assertEquals(listOf("小盈，", "后面的话。"), texts)
                assertFalse(states.any { it.contains("失败") })
            } finally { job.cancelAndJoin(); chunks.cancel() }
        }
    }

    @Test fun segmentedReplyHoldsOneLeaseUntilPlayerDrains() = runBlocking {
        Fixture().use { f -> MockWebServer().use { control ->
            control.enqueue(MockResponse().setBody("""{"lease_id":"lease-segments-123"}"""))
            repeat(3) { control.enqueue(MockResponse().setBody("""{"state":"ready","busy":false,"restart_required":false,"low_latency_ready":true,"named_voices":{"index2-male-01":{"ready":true}}}""")) }
            control.enqueue(MockResponse().setBody("""{"released":true}"""))
            val selected = wholeConfig("index2-male-01", "client_segments").copy(ttsModel = "indextts2")
            val waitingDrain = CompletableDeferred<Unit>()
            val drained = CompletableDeferred<Unit>()
            val input = Channel<String>(2).apply { trySend("第一，"); trySend("第二。"); close() }
            val lifecycle = QwenLifecycle(selected, f.client, control.url("/"), pollMillis = 1)
            val job = async { QwenSpeech(selected, f.factory, lifecycle = lifecycle).speakStream(input,
                onPlayed = { waitingDrain.complete(Unit); drained.await() }) {} }
            try {
                withTimeout(5000) { waitingDrain.await() }
                assertEquals(2, f.requests.size)
                assertEquals("/v1/audio/activate", control.takeRequest().path)
                assertEquals("/health", control.takeRequest().path)
                repeat(2) { assertEquals("/health",control.takeRequest().path) }
                assertNull(control.takeRequest(100, TimeUnit.MILLISECONDS))
                drained.complete(Unit)
                withTimeout(3000) { job.await() }
                assertEquals("/v1/audio/release", control.takeRequest(2, TimeUnit.SECONDS)!!.path)
                assertEquals(5, control.requestCount)
            } finally { job.cancelAndJoin() }
        } }
    }

    @Test fun cancellingBlockedPlayerDropsPrefetchAndNextReplyUsesFreshRequest() = runBlocking {
        Fixture().use { f ->
            val selected = wholeConfig("index2-male-01", "client_segments").copy(ttsModel = "indextts2")
            val input = Channel<String>(3).apply { trySend("第一，"); trySend("第二。"); trySend("不应生成的第三段。"); close() }
            val writing = CompletableDeferred<Unit>()
            var aborted = false
            val job = launch { QwenSpeech(selected, f.factory).speakStream(input, onAbort = { aborted = true }) {
                writing.complete(Unit)
                Thread.sleep(30_000)
                fail("cancelled player must not resume")
            } }
            try {
                withTimeout(5000) { writing.await(); while (f.requests.size < 2) delay(5) }
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(aborted)
                assertEquals(2, f.requests.size)
                assertFalse(f.messages.any { it.contains("不应生成") })
                val fresh = Channel<String>(1).apply { trySend("新回复。"); close() }
                var bytes = 0
                withTimeout(3000) { QwenSpeech(selected, f.factory).speakStream(fresh) { bytes += it.size } }
                assertEquals(4, bytes)
                assertEquals(3, f.requests.size)
            } finally { job.cancelAndJoin(); input.cancel() }
        }
    }

    @Test fun cancellingBeforeFirstPcmAbortsThenReleasesOneLease() = runBlocking {
        Fixture("hold").use { f -> MockWebServer().use { control ->
            control.enqueue(MockResponse().setBody("""{"lease_id":"lease-segments-123"}"""))
            repeat(3) { control.enqueue(MockResponse().setBody("""{"state":"ready","busy":false,"restart_required":false,"low_latency_ready":true,"named_voices":{"index2-male-01":{"ready":true}}}""")) }
            control.enqueue(MockResponse().setBody("""{"released":true}"""))
            val selected = wholeConfig("index2-male-01", "client_segments").copy(ttsModel = "indextts2")
            val input = Channel<String>(2).apply { trySend("第一，"); trySend("后面的。"); close() }
            var aborted = false
            val job = launch { QwenSpeech(selected, f.factory,
                lifecycle = QwenLifecycle(selected, f.client, control.url("/"), pollMillis = 1))
                .speakStream(input, onAbort = {
                    assertEquals("must clear player before release", 3, control.requestCount)
                    aborted = true
                }) { fail("no audio from held synthesis") } }
            try {
                withTimeout(5000) { while (f.messages.none { it.contains("input.done") }) delay(5) }
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(aborted)
                assertEquals(1, f.requests.size)
                control.takeRequest(); control.takeRequest()
                repeat(2) { assertEquals("/health",control.takeRequest().path) }
                assertEquals("/v1/audio/release", control.takeRequest(2, TimeUnit.SECONDS)!!.path)
            } finally { job.cancelAndJoin(); input.cancel() }
        } }
    }

    @Test fun clientSegmentsSendDoneBeforeReplyEndsAndKeepExactNamedVoice() = runBlocking {
        Fixture().use { f ->
            val input = Channel<String>(4)
            val audio = Channel<ByteArray>(4)
            val selected = wholeConfig("index2-male-01", "client_segments").copy(ttsModel = "indextts2")
            val job = async { QwenSpeech(selected, f.factory).speakStream(input) { audio.trySend(it) } }
            try {
                input.send("小盈，")
                assertArrayEquals(byteArrayOf(1,2,3,4), withTimeout(3000) { audio.receive() })
                assertTrue("Tool wait must leave the reply open", job.isActive)
                input.send("后续合并的一段。")
                input.close()
                withTimeout(3000) { job.await() }
                assertArrayEquals(byteArrayOf(1,2,3,4), audio.receive())
                val setups = f.messages.map { JsonParser.parseString(it).asJsonObject }
                    .filter { it["type"].asString == "session.config" }
                assertEquals(2, setups.size)
                setups.forEach {
                    assertEquals("none", it["split_granularity"].asString)
                    assertEquals("indextts2", it["model"].asString)
                    assertEquals("index2-male-01", it["voice"].asString)
                }
                assertEquals(2, f.messages.count { it.contains("input.done") })
            } finally { job.cancelAndJoin(); input.cancel(); audio.cancel() }
        }
    }

    @Test fun clientSegmentProfileRoundTripPreservesOriginalWholeProfile() {
        val gson = com.google.gson.Gson()
        val original = MobileSettings(wholeConfig(), chatId = "keep-chat").saveVoiceProfile("整段")
        val segmented = wholeConfig("index2-male-01", "client_segments").copy(ttsModel = "indextts2")
        val saved = original.copy(speech = segmented).saveVoiceProfile("首句先读")
        val restored = gson.fromJson(gson.toJson(saved), MobileSettings::class.java)
        assertEquals(segmented, restored.selectVoiceProfile(restored.profiles().last().id).speech)
        assertEquals(wholeConfig(), restored.selectVoiceProfile(restored.profiles().first().id).speech)
        assertEquals("keep-chat", restored.chatId)
    }

    private fun wholeConfig(voice: String = "clone-1", mode: String = "none"): SpeechConfig {
        val gson = com.google.gson.Gson()
        val json = gson.toJsonTree(config.copy(voice = voice)).asJsonObject
        json.addProperty("qwenSplitGranularity", mode)
        return gson.fromJson(json, SpeechConfig::class.java)
    }

    @Test fun wholeModePersistsSeparatelyAndRejectsInvalidMode() {
        val gson = com.google.gson.Gson()
        val original = MobileSettings(config, chatId = "existing-chat").saveVoiceProfile("A")
        val saved = original.copy(speech = wholeConfig()).saveVoiceProfile("克隆1整段")
        val restored = gson.fromJson(gson.toJson(saved), MobileSettings::class.java)
        val selected = restored.selectVoiceProfile(restored.profiles().last().id)
        assertEquals("none", gson.toJsonTree(selected.speech).asJsonObject["qwenSplitGranularity"]?.asString)
        assertEquals(config, restored.selectVoiceProfile(restored.profiles().first().id).speech)
        assertEquals("existing-chat", restored.chatId)
        assertTrue(wholeConfig().validationErrors(false).isEmpty())
        assertTrue(wholeConfig("xiaoying-a").validationErrors(false).isEmpty())
        assertTrue(wholeConfig(mode = "invalid").validationErrors(false).isNotEmpty())
    }

    @Test fun indexNamedVoiceUsesExistingWireContractWithoutAliasSubstitution() = runBlocking {
        Fixture().use { f ->
            val next = wholeConfig("index-reference-2").copy(ttsModel = "indextts2")
            val input = Channel<String>(1).apply { trySend("这是新的具名声音。"); close() }
            var received = 0
            withTimeout(5000) {
                CloudSpeech(next, f.client, f.factory).speakStream(input) { received += it.size }
            }
            val setup = JsonParser.parseString(f.messages.first()).asJsonObject
            assertEquals("indextts2", setup["model"].asString)
            assertEquals("index-reference-2", setup["voice"].asString)
            assertEquals("none", setup["split_granularity"].asString)
            assertEquals(1, f.messages.count { it.contains("input.done") })
            assertEquals(4, received)
        }
    }

    @Test fun wholeModeFinishesInputWithoutWaitingForAudioAndProducesOneUnit() = runBlocking {
        Fixture().use { f ->
            val input = Channel<String>(4)
            val audio = Channel<ByteArray>(4)
            val job = async { CloudSpeech(wholeConfig(), f.client, f.factory).speakStream(input) { audio.trySend(it) } }
            try {
                input.send("第一句。")
                withTimeout(5000) { while (f.messages.none { it.contains("input.text") }) delay(5) }
                assertEquals("none", JsonParser.parseString(f.messages.first()).asJsonObject["split_granularity"].asString)
                assertNull(withTimeoutOrNull(100) { audio.receive() })
                input.send("第二句。")
                input.close()
                withTimeout(5000) { job.await() }
                assertArrayEquals(byteArrayOf(1,2,3,4), audio.receive())
                assertTrue(audio.tryReceive().isFailure)
                assertEquals(1, f.requests.size)
                assertEquals(1, f.messages.count { it.contains("input.done") })
            } finally { job.cancelAndJoin(); input.cancel(); audio.cancel() }
        }
    }

    @Test fun wholeModeCanCancelWhileBufferingAndFollowingReplyUsesFreshSocket() = runBlocking {
        Fixture().use { f ->
            val input = Channel<String>(2)
            val job = launch { CloudSpeech(wholeConfig(), f.client, f.factory).speakStream(input) { fail("No audio before done") } }
            try {
                input.send("缓冲中的第一句。")
                withTimeout(5000) { while (f.messages.none { it.contains("input.text") }) delay(5) }
                assertEquals("none", JsonParser.parseString(f.messages.first()).asJsonObject["split_granularity"].asString)
                withTimeout(1000) { job.cancelAndJoin() }
                assertFalse(f.messages.any { it.contains("input.done") })
                assertNotNull(withContext(Dispatchers.IO) { f.disconnected.poll(3, TimeUnit.SECONDS) })
                val next = Channel<String>(1).apply { trySend("新的回复。"); close() }
                var bytes = 0
                withTimeout(5000) { CloudSpeech(wholeConfig(), f.client, f.factory).speakStream(next) { bytes += it.size } }
                assertEquals(4, bytes)
                assertEquals(2, f.requests.size)
            } finally { job.cancelAndJoin(); input.cancel() }
        }
    }
    private val config = SpeechConfig.bailianDefaults().copy(ttsProvider = "qwen-local",
        ttsBaseUrl = "wss://voice.example.ts.net/v1/audio/speech/stream", ttsKey = "TTS-ONLY",
        ttsModel = "qwen-a-clone", voice = "xiaoying-a", sttKey = "ASR-ONLY")

    @Test fun configSupportsStreamingWithoutChangingRecognitionOrOtherSavedProfiles() {
        assertEquals(emptyList<String>(), config.validationErrors(false))
        assertTrue(config.streamingTts)
        val original = SpeechConfig.bailianDefaults().copy(sttKey = "ASR", ttsKey = "CLOUD")
        val next = original.withTtsProvider("qwen-local")
        assertEquals("ASR", next.sttKey)
        assertEquals(original.provider, next.provider)
        assertEquals("", next.ttsKey)
        assertEquals("xiaoying-a", next.voice)
        assertEquals("qwen-a-clone", next.ttsModel)
        assertEquals("CLOUD", original.ttsKey)
    }

    @Test fun rejectsPlaintextCredentialsInUrlAndMalformedNamedIds() {
        listOf("ws://voice.test/v1/audio/speech/stream", "wss://user:secret@voice.test/v1/audio/speech/stream",
            "wss://voice.test/v1/audio/speech/stream?token=secret", "wss://voice.test/wrong").forEach {
            assertTrue(config.copy(ttsBaseUrl = it).validationErrors(false).isNotEmpty())
        }
        listOf("../reference.wav", "C:\\private.wav", "voice id", "https://host/voice", "a".repeat(129)).forEach {
            assertTrue(config.copy(voice = it).validationErrors(false).isNotEmpty())
            assertTrue(config.copy(ttsModel = it).validationErrors(false).isNotEmpty())
        }
    }

    @Test fun namedCloneSurvivesSavingAndSwitchingWithoutChangingOriginalVoiceOrChat() {
        val original = MobileSettings(config, chatId = "existing-chat").saveVoiceProfile("A")
        val clone = config.copy(voice = "clone-1", ttsModel = "qwen-base-clone")
        assertEquals(emptyList<String>(), clone.validationErrors(false))
        val saved = original.copy(speech = clone).saveVoiceProfile("克隆1")
        val restored = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(saved), MobileSettings::class.java)
        assertEquals(clone, restored.selectVoiceProfile(restored.profiles().last().id).speech)
        assertEquals(config, restored.selectVoiceProfile(restored.profiles().first().id).speech)
        assertEquals("existing-chat", restored.chatId)
        assertEquals(config.sttKey, restored.speech.sttKey)
    }

    @Test fun streamsBeforeTextEndsAndDoesNotTurnEachFragmentIntoSentence() = runBlocking {
        Fixture().use { f ->
            val texts = Channel<String>(8)
            val audio = Channel<ByteArray>(8)
            val job = async { CloudSpeech(config.copy(voice = "clone-1"), f.client, f.factory).speakStream(texts) { audio.trySend(it) } }
            texts.send("宝宝，")
            texts.send("我来啦。")
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), withTimeout(5000) { audio.receive() })
            assertFalse(job.isCompleted)
            assertFalse(f.messages.any { it.contains("input.done") })
            texts.send("下一句没标点")
            texts.close()
            withTimeout(5000) { job.await() }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.receive())
            val messages = f.messages.map { JsonParser.parseString(it).asJsonObject }
            val setup = messages.first()
            assertEquals("session.config", setup["type"].asString)
            assertEquals("clone-1", setup["voice"].asString)
            assertEquals("qwen-a-clone", setup["model"].asString)
            assertFalse(setup.has("ref_audio")); assertFalse(setup.has("ref_text"))
            val input = messages.filter { it["type"].asString == "input.text" }.joinToString("") { it["text"].asString }
            assertEquals("宝宝，我来啦。\n下一句没标点", input)
            assertEquals(1, messages.count { it["type"].asString == "input.done" })
            assertEquals("Bearer TTS-ONLY", f.requests.single().header("Authorization"))
            assertFalse(messages.toString().contains("ASR-ONLY"))
            texts.cancel(); audio.cancel()
        }
    }

    @Test fun failsOnWrongByteCountsMissingFinalAndProviderErrorWithoutLeakingPayload() = runBlocking {
        for (mode in listOf("wrong-bytes", "missing-final", "error", "wrong-rate", "wrong-index", "odd-pcm", "wrong-total")) {
            Fixture(mode).use { f ->
                val texts = Channel<String>(1).apply { trySend("测试。"); close() }
                val failure = try {
                    withTimeout(5000) { CloudSpeech(config, f.client, f.factory).speakStream(texts) {} }
                    null
                } catch (e: SpeechApiException) { e }
                assertNotNull("Should reject $mode", failure)
                assertFalse(failure!!.message.orEmpty().contains("SECRET-SERVER-PAYLOAD"))
            }
        }
    }

    @Test fun traceSeparatesTextWaitingFromAudioArrivalWithoutPrivateContent() = runBlocking {
        Fixture().use { f ->
            VoiceDiagnostics.record("qwen_trace_test_start")
            val texts = Channel<String>(2)
            val audio = Channel<ByteArray>(2)
            val job = async { CloudSpeech(config, f.client, f.factory).speakStream(texts) { audio.trySend(it) } }
            try {
                texts.send("计时私有正文，")
                withTimeout(5000) { while (f.messages.isEmpty()) delay(10) }
                val waiting = VoiceDiagnostics.snapshot().substringAfterLast("qwen_trace_test_start")
                assertTrue(waiting.contains("qwen_first_fragment"))
                assertTrue(waiting.contains("qwen_socket_open"))
                assertFalse(waiting.contains("qwen_first_text_queued"))
                texts.send("下一句。")
                withTimeout(5000) { audio.receive() }
                val received = VoiceDiagnostics.snapshot().substringAfterLast("qwen_trace_test_start")
                assertTrue(received.contains("qwen_first_sentence_ready"))
                assertTrue(received.contains("qwen_first_text_queued"))
                assertTrue(received.contains("qwen_first_pcm_received"))
                assertFalse(received.contains("计时私有正文"))
                assertFalse(received.contains("TTS-ONLY"))
                assertFalse(received.contains("voice.example"))
                assertFalse(job.isCompleted)
                texts.close()
                withTimeout(5000) { job.await() }
            } finally { job.cancelAndJoin(); texts.cancel(); audio.cancel() }
        }
    }

    @Test fun mapsHandshakeErrorsWithoutRetries() = runBlocking {
        for ((code, expected) in listOf(401 to "令牌", 403 to "令牌", 429 to "占用")) {
            Fixture(status = code).use { f ->
                val texts = Channel<String>(1).apply { trySend("测试。"); close() }
                val failure = try {
                    CloudSpeech(config, f.client, f.factory).speakStream(texts) {}; null
                } catch (e: SpeechApiException) { e }
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains(expected))
                assertEquals(1, f.requests.size)
            }
        }
    }

    @Test fun initial503RecoversWithoutLosingOrDuplicatingText() = runBlocking {
        Fixture(rejections = 1).use { f ->
            val input = Channel<String>(3).apply { trySend("第一句。"); trySend("第二句。"); close() }
            var bytes = 0
            val statuses = mutableListOf<String>()
            withTimeout(10000) { QwenSpeech(wholeConfig(), f.factory, onStatus = { statuses += it })
                .speakStream(input) { bytes += it.size } }
            assertTrue(statuses.first().contains("等待重连"))
            assertTrue(statuses.last().contains("已连接"))
            assertEquals(2, f.requests.size)
            val messages = f.messages.map { JsonParser.parseString(it).asJsonObject }
            assertEquals(1, messages.count { it["type"].asString == "session.config" })
            assertEquals(1, messages.count { it["type"].asString == "input.done" })
            assertEquals("第一句。第二句。", messages.filter { it["type"].asString == "input.text" }
                .joinToString("") { it["text"].asString }.replace("\n", ""))
            assertEquals(4, bytes)
        }
    }

    @Test fun leaseIsReleasedOnlyAfterPlaybackCallbackAndCannotReplayText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"state":"ready","low_latency_ready":true,"lease_id":"lease-12345678"}"""))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"state":"ready","low_latency_ready":true,"named_voices":{"clone-1":{"ready":true}}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"released":true}"""))
            Fixture().use { f ->
                val input = Channel<String>(1).apply { trySend("只说一次。"); close() }
                var played = false
                var bytes = 0
                val lifecycle = QwenLifecycle(wholeConfig(), f.client, server.url("/"), pollMillis = 1)
                QwenSpeech(wholeConfig(), f.factory, lifecycle = lifecycle).speakStream(input,
                    onPlayed = {
                        assertEquals(2, server.requestCount)
                        assertEquals(4, bytes)
                        played = true
                    }) { bytes += it.size }
                assertTrue(played)
                assertEquals("/v1/audio/activate", server.takeRequest().path)
                assertEquals("/health", server.takeRequest().path)
                assertEquals("/v1/audio/release", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                assertEquals(1, f.messages.count { it.contains("input.done") })
            }
        }
    }

    @Test fun failureAfterSocketOpenNeverReplaysEvenWith503() = runBlocking {
        var attempts = 0
        var cancelled = false
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://voice.example.ts.net").build()
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: okio.ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() { cancelled = true }
        }
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                attempts++
                val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(101).message("Switching Protocols").body("".toResponseBody()).build()
                listener.onOpen(socket, response)
                listener.onFailure(socket, java.io.IOException("private"),
                    response.newBuilder().code(503).build())
                return socket
            }
        }
        val input = Channel<String>(1).apply { trySend("不得重放。"); close() }
        try { QwenSpeech(config, factory).speakStream(input) {}; fail("must fail") }
        catch (_: SpeechApiException) { }
        assertEquals(1, attempts)
        assertTrue(cancelled)
    }

    @Test fun persistent503StopsAfterThreeConnectionsWithoutSendingText() = runBlocking {
        Fixture(status = 503).use { f ->
            val input = Channel<String>(1).apply { trySend("测试。"); close() }
            val failure = try {
                withTimeout(12000) { QwenSpeech(config, f.factory).speakStream(input) {} }; null
            } catch (e: SpeechApiException) { e }
            assertNotNull(failure)
            assertEquals(3, f.requests.size)
            assertTrue(f.messages.isEmpty())
        }
    }

    @Test fun cancelDuring503WaitDoesNotReconnect() = runBlocking {
        Fixture(status = 503).use { f ->
            val input = Channel<String>(1).apply { trySend("测试。"); close() }
            val job = async { QwenSpeech(config, f.factory).speakStream(input) {} }
            withTimeout(2000) { while (f.requests.isEmpty()) delay(5) }
            delay(150)
            assertTrue("503 must leave a cancellable waiting period", job.isActive)
            job.cancelAndJoin()
            delay(2200)
            assertEquals(1, f.requests.size)
            assertTrue(f.messages.isEmpty())
        }
    }

    @Test fun cancellationDisconnectsWhileWaitingForTextAndNextReplyUsesFreshSocket() = runBlocking {
        Fixture().use { f ->
            val input = Channel<String>(2)
            val audio = Channel<ByteArray>(8)
            val job = launch { CloudSpeech(config, f.client, f.factory).speakStream(input) { audio.trySend(it) } }
            input.send("第一条。")
            withTimeout(5000) { audio.receive() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertNotNull(withContext(Dispatchers.IO) { f.disconnected.poll(3, TimeUnit.SECONDS) })
            val next = Channel<String>(1).apply { trySend("下一条。"); close() }
            withTimeout(5000) { CloudSpeech(config, f.client, f.factory).speakStream(next) { audio.trySend(it) } }
            assertEquals(2, f.requests.size)
            input.cancel(); audio.cancel()
        }
    }

    @Test fun emptyInputDoesNotConnect() = runBlocking {
        Fixture().use { f ->
            val input = Channel<String>(1).apply { close() }
            CloudSpeech(config, f.client, f.factory).speakStream(input) { fail("unexpected PCM") }
            assertEquals(0, f.requests.size)
        }
    }

    @Test fun cancellationBeforeFirstPcmReleasesSocketWithoutWaitingForProvider() = runBlocking {
        Fixture("hold").use { f ->
            val input = Channel<String>(1).apply { trySend("测试。"); close() }
            val job = launch { CloudSpeech(config, f.client, f.factory).speakStream(input) { fail("unexpected PCM") } }
            withTimeout(5000) { while (f.messages.none { it.contains("input.done") }) delay(5) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertNotNull(withContext(Dispatchers.IO) { f.disconnected.poll(3, TimeUnit.SECONDS) })
        }
    }

    @Test fun cancellationInterruptsBlockedPcmConsumer() = runBlocking {
        Fixture().use { f ->
            val input = Channel<String>(1).apply { trySend("测试。"); close() }
            val writing = CompletableDeferred<Unit>()
            val job = launch { CloudSpeech(config, f.client, f.factory).speakStream(input) {
                writing.complete(Unit)
                Thread.sleep(30_000)
                fail("Canceled audio write must not finish")
            } }
            withTimeout(5000) { writing.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun stalledResponseHasBoundedTimeoutAndDisconnects() = runBlocking {
        Fixture("hold").use { f ->
            val input = Channel<String>(1).apply { trySend("测试。"); close() }
            val failure = try {
                QwenSpeech(config, f.factory, timeoutMillis = 500).speakStream(input) {}; null
            } catch (e: SpeechApiException) { e }
            assertTrue(failure?.message.orEmpty().contains("超时"))
            assertEquals(1, f.requests.size)
            assertNotNull(withContext(Dispatchers.IO) { f.disconnected.poll(3, TimeUnit.SECONDS) })
        }
    }

    @Test fun longReplyUsesBoundedFramesWithoutInventingExtraSentences() = runBlocking {
        Fixture().use { f ->
            val body = "甲".repeat(8000) + "。" + "乙".repeat(8000) + "。"
            val input = Channel<String>(1).apply { trySend(body); close() }
            var bytes = 0
            withTimeout(5000) { CloudSpeech(config, f.client, f.factory).speakStream(input) { bytes += it.size } }
            val parts = f.messages.map { JsonParser.parseString(it).asJsonObject }
                .filter { it["type"].asString == "input.text" }.map { it["text"].asString }
            assertTrue(parts.all { it.codePointCount(0, it.length) <= 4096 })
            assertEquals(body, parts.joinToString("").replace("\n", ""))
            assertEquals(2, parts.joinToString("").count { it == '\n' })
            assertEquals(8, bytes)
        }
    }

    @Test fun lateCallbackAfterTransportFailureCannotThrowOnSocketThread() = runBlocking {
        var escaped: Exception? = null
        var canceled = false
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://voice.example.ts.net").build()
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: okio.ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() { canceled = true }
        }
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                listener.onFailure(socket, java.io.IOException("private transport detail"), null)
                try { listener.onMessage(socket, "late event") } catch (e: Exception) { escaped = e }
                return socket
            }
        }
        val texts = Channel<String>(1).apply { trySend("测试。"); close() }
        try { CloudSpeech(config, webSockets = factory).speakStream(texts) {}; fail("must report failure") }
        catch (_: SpeechApiException) { /* Report through the coroutine, never a callback exception. */ }
        assertNull("Closed event queue must reject late callbacks quietly", escaped)
        assertTrue(canceled)
    }

    private class Fixture(private val mode: String = "ok", status: Int = 101, rejections: Int = 0) : AutoCloseable {
        val messages = LinkedBlockingQueue<String>()
        val requests = LinkedBlockingQueue<Request>()
        val disconnected = LinkedBlockingQueue<Boolean>()
        private val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        init {
            repeat(3) { attempt ->
                if (attempt < rejections) server.enqueue(MockResponse().setResponseCode(503))
                else if (status != 101) server.enqueue(MockResponse().setResponseCode(status))
                else server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    var sentence = 0
                    var pending = ""
                    var whole = false
                    fun sendAudio(ws: WebSocket) {
                        if (mode == "error") { ws.send("""{"type":"error","message":"SECRET-SERVER-PAYLOAD"}"""); return }
                        val rate = if (mode == "wrong-rate") 48000 else 24000
                        val index = if (mode == "wrong-index") 99 else sentence
                        ws.send("""{"type":"audio.start","utterance_index":0,"sentence_index":$index,"format":"pcm","sample_rate":$rate}""")
                        ws.send((if (mode == "odd-pcm") byteArrayOf(1,2,3) else byteArrayOf(1,2,3,4)).toByteString())
                        val size = if (mode == "wrong-bytes") 100 else 4
                        ws.send("""{"type":"audio.done","utterance_index":0,"sentence_index":$sentence,"total_bytes":$size,"error":false}""")
                        sentence++
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        messages.add(text)
                        if (mode == "hold") return
                        val json = JsonParser.parseString(text).asJsonObject
                        when (json["type"].asString) {
                            "session.config" -> whole = json["split_granularity"].asString == "none"
                            "input.text" -> {
                                pending += json["text"].asString
                                while (!whole && '\n' in pending) { sendAudio(ws); pending = pending.substringAfter('\n') }
                            }
                            "input.done" -> {
                                if (pending.isNotBlank()) { sendAudio(ws); pending = "" }
                                if (mode == "missing-final") ws.close(1000, "done")
                                else {
                                    val count = if (mode == "wrong-total") 99 else sentence
                                    ws.send("""{"type":"session.done","utterance_index":0,"total_sentences":$count}""")
                                }
                            }
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { disconnected.offer(true) }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) { disconnected.offer(true); ws.close(code, null) }
                }))
            }
            server.start()
        }
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                requests.add(request)
                return client.newWebSocket(request.newBuilder().url(server.url("/v1/audio/speech/stream")).build(), listener)
            }
        }
        override fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
