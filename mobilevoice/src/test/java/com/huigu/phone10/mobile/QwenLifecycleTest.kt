package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class QwenLifecycleTest {
    private val config = SpeechConfig.bailianDefaults().copy(ttsProvider = SpeechConfig.QWEN_LOCAL,
        ttsBaseUrl = "wss://voice.example.ts.net/v1/audio/speech/stream", ttsKey = "TTS-ONLY",
        ttsModel = "qwen-a-clone", voice = "clone-1")
    private fun reply(code: Int, json: String) = MockResponse().setResponseCode(code)
        .addHeader("Content-Type", "application/json").setBody(json)
    private fun ready(voice: Boolean = true) =
        """{"state":"ready","low_latency_ready":true,"named_voices":{"clone-1":{"ready":$voice}}}"""

    @Test fun waitsForNamedVoiceAndReleasesOnlyAfterPlayback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(reply(202,"""{"state":"warming","low_latency_ready":false,"lease_id":"lease-12345678"}"""))
            server.enqueue(reply(503,"""{"state":"warming","low_latency_ready":false}"""))
            server.enqueue(reply(200,ready(false)))
            server.enqueue(reply(200,ready()))
            server.enqueue(reply(200,"""{"released":true}"""))
            val lifecycle = QwenLifecycle(config, OkHttpClient(), server.url("/"), pollMillis = 1)
            val statuses = mutableListOf<String>()
            val lease = withTimeout(5000) { lifecycle.activateAndWait { statuses += it } }
            assertEquals("lease-12345678",lease)
            assertTrue(statuses.single().contains("准备中"))
            assertEquals(4,server.requestCount)
            assertEquals("/v1/audio/activate",server.takeRequest().path)
            assertEquals("/health",server.takeRequest().path)
            server.takeRequest();server.takeRequest()
            lifecycle.release(lease)
            val release = server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/v1/audio/release",release.path)
            assertEquals("Bearer TTS-ONLY",release.getHeader("Authorization"))
            assertTrue(release.body.readUtf8().contains("lease-12345678"))
        }
    }

    @Test fun manualOffDoesNotWakeOrRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(reply(423,"""{"error":{"code":"TTS_MANUALLY_OFF"}}"""))
            val lifecycle = QwenLifecycle(config, OkHttpClient(), server.url("/"), pollMillis = 1)
            val error = try { lifecycle.activateAndWait {}; null } catch (e: SpeechApiException) { e }
            assertTrue(error!!.message.orEmpty().contains("手动关闭"))
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun cancellationDuringWarmupReleasesLeaseWithoutOpeningSocket() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(reply(202,"""{"state":"warming","lease_id":"lease-12345678"}"""))
            server.enqueue(reply(503,"""{"state":"warming"}"""))
            server.enqueue(reply(200,"""{"released":true}"""))
            val lifecycle = QwenLifecycle(config, OkHttpClient(), server.url("/"), pollMillis = 30_000)
            val job = launch { lifecycle.activateAndWait {} }
            withTimeout(3000) { while (server.requestCount < 2) delay(5) }
            job.cancelAndJoin()
            assertEquals("/v1/audio/activate",server.takeRequest().path)
            assertEquals("/health",server.takeRequest().path)
            assertEquals("/v1/audio/release",server.takeRequest(3,TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun failedStartReleasesLeaseAndDoesNotPretendSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(reply(202,"""{"state":"warming","lease_id":"lease-12345678"}"""))
            server.enqueue(reply(503,"""{"state":"failed","error":{"code":"MODEL_START_TIMEOUT"}}"""))
            server.enqueue(reply(200,"""{"released":true}"""))
            val lifecycle = QwenLifecycle(config, OkHttpClient(), server.url("/"), pollMillis = 1)
            val error = try { lifecycle.activateAndWait {}; null } catch (e: SpeechApiException) { e }
            assertTrue(error!!.message.orEmpty().contains("启动失败"))
            server.takeRequest();server.takeRequest()
            assertEquals("/v1/audio/release",server.takeRequest(3,TimeUnit.SECONDS)!!.path)
        }
    }
}
