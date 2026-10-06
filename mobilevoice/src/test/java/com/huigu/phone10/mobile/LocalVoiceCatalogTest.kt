package com.huigu.phone10.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LocalVoiceCatalogTest {
    private val config = SpeechConfig.openAiDefaults().copy(sttKey="recognition-test", ttsProvider = SpeechConfig.QWEN_LOCAL,
        ttsBaseUrl = "wss://example.test/v1/audio/speech/stream", ttsKey = "private-test-key",
        ttsModel = "indextts2", voice = "index2-male-mix-02", qwenSplitGranularity = "client_segments")
    private val body = """{"voices":[
        {"id":"index2-male-01","name":"旧声","model":"indextts2","ready":true,"supported_modes":["none"]},
        {"id":"index2-male-mix-02","name":"中混合","model":"indextts2","ready":true,"supported_modes":["none"]},
        {"id":"clone-1","name":"克隆1","model":"qwen-a-clone","ready":false}]}"""
    @Test fun authenticatedCatalogKeepsUnavailableEntriesAndAcceptsNamedMix() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val entries = QwenLifecycle(config, OkHttpClient(), server.url("/")).voices()
            assertEquals(3, entries.size)
            requireLocalVoice(entries, config)
            assertFalse(entries.last().ready)
            assertTrue(entries.last().supportedModes.isEmpty())
            val request = server.takeRequest()
            assertEquals("/v1/audio/voices", request.path)
            assertEquals("Bearer private-test-key", request.getHeader("Authorization"))
        }
    }
    @Test fun unknownUnavailableAndUnsupportedVoicesDoNotFallback() {
        val entries = LocalVoice.parseCatalog(body)
        for (candidate in listOf(config.copy(voice="unknown"), config.copy(voice="clone-1",ttsModel="qwen-a-clone"),
                config.copy(qwenSplitGranularity="sentence"),config.copy(ttsModel="other"))) {
            assertTrue(runCatching { requireLocalVoice(entries,candidate) }.isFailure)
        }
    }
    @Test fun malformedAndDuplicateEntriesAreRejectedWithoutPartialCatalog() {
        val voice="""{"id":"one","name":"声音","model":"indextts2","ready":true,"supported_modes":["none"]}"""
        for (raw in listOf("not-json", "{}", """{"voices":[$voice,$voice]}""",
            """{"voices":[${voice.replace("true", "\"true\"")}]}""")) {
            assertTrue(runCatching { LocalVoice.parseCatalog(raw) }.exceptionOrNull() is SpeechApiException)
        }
    }
    @Test fun authorizationFailureDoesNotActivateOrSaveAnything() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val failure=runCatching { QwenLifecycle(config,OkHttpClient(),server.url("/")).voices() }.exceptionOrNull()
            assertTrue(failure is SpeechApiException)
            assertFalse(failure!!.message.orEmpty().contains(config.ttsKey))
            assertEquals(1,server.requestCount)
            assertEquals("/v1/audio/voices",server.takeRequest().path)
        }
    }
    @Test fun addingVoiceKeepsOldProfileCredentialsAndRecognition() {
        val old = MobileSettings(speech = config.copy(voice="index2-male-01"))
        val saved = old.saveVoiceProfile("旧声音")
        val next = saved.addLocalVoice(config, LocalVoice.parseCatalog(body)[1])
        assertEquals(2,next.profiles().size)
        assertEquals("index2-male-01",next.profiles().first().speech.voice)
        assertEquals("index2-male-mix-02",next.speech.voice)
        assertEquals(config.ttsKey,next.speech.ttsKey)
        assertEquals(saved.speech.sttModel,next.speech.sttModel)
        assertEquals(next,next.addLocalVoice(config, LocalVoice.parseCatalog(body)[1]))
    }
}
