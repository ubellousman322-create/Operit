package com.huigu.phone10.mobile

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MossSpeechTest {
    @Test fun savedSpeedReachesEverySynthesisRequestWhileLegacyVoiceKeepsDefault() = runBlocking {
        val gson = Gson()
        fun configured(speed: Double?): SpeechConfig {
            val json = gson.toJsonTree(config()).asJsonObject
            if (speed != null) json.addProperty("mossSpeed", speed) else json.remove("mossSpeed")
            return gson.fromJson(json, SpeechConfig::class.java)
        }
        val legacy = configured(null)
        val normal = MobileSettings(legacy).saveVoiceProfile("原速")
        val both = normal.copy(speech = configured(1.2)).saveVoiceProfile("稍快")
        val restored = gson.fromJson(gson.toJson(both), MobileSettings::class.java)
        for (profile in restored.profiles()) {
            val selected = restored.selectVoiceProfile(profile.id).speech
            val expected = if (profile.name == "稍快") 1.2 else 1.0
            var requests = 0
            val audio = mutableListOf<Byte>()
            val cloud = CloudSpeech(selected, client { request ->
                val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                assertEquals(expected, body["speed"]?.asDouble ?: 1.0, 0.000001)
                assertEquals("voice-id", body["voice_id"].asString)
                requests++
                created() + delta + done
            })
            cloud.speak("前半句") { audio.addAll(it.toList()) }
            cloud.speak("后半句") { audio.addAll(it.toList()) }
            assertEquals(2, requests)
            assertEquals(listOf<Byte>(1, 2, 3, 4, 1, 2, 3, 4), audio)
        }
    }

    @Test fun rejectsOutOfRangeMossSpeedBeforeSavingProfile() {
        val gson = Gson()
        for (speed in listOf(0.0, 0.24, 4.01, 99.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val json = gson.toJsonTree(config()).asJsonObject.apply { addProperty("mossSpeed", speed) }
            val invalid = gson.fromJson(json, SpeechConfig::class.java)
            assertThrows(IllegalArgumentException::class.java) { MobileSettings(invalid).saveVoiceProfile("无效") }
        }
    }

    @Test fun nextHttpFailurePreservesCurrentAudioAndRecordsSafeReason() = runBlocking {
        val nextRequest = CompletableDeferred<Unit>()
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            if (count == 2) nextRequest.complete(Unit)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (count == 2) 429 else 200).message("test")
                .body((if (count == 2) "private-provider-detail" else created(48000) + delta + done)
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val cloud = CloudSpeech(config(), http)
        val input = kotlinx.coroutines.channels.Channel<String>(2).apply {
            trySend("第一句。"); trySend("第二句。"); close()
        }
        val audio = mutableListOf<Byte>()
        try {
            withTimeout(3000) {
                SentenceSpeech(cloud::speak).speak(input, {}) {
                    nextRequest.await(); delay(20); audio.addAll(it.toList())
                }
            }
            fail("HTTP failure must remain visible")
        } catch (error: SpeechApiException) {
            assertTrue(error.message.orEmpty().contains("429"))
        }
        assertEquals(listOf<Byte>(1, 2, 3, 4), audio)
        val events = VoiceDiagnostics.snapshot()
        assertTrue(events.contains("status_429"))
        assertFalse(events.contains("private-provider-detail"))
        assertFalse(events.contains("moss-test-key"))
        assertFalse(events.contains("第一句"))
    }

    @Test fun queuedHttpSpeechPrefetchesWithoutReconfiguringPlayback() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val nextRequest = CompletableDeferred<Unit>()
        val cloud = CloudSpeech(config(), client { request ->
            val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            requests.add(body["input"].asString)
            if (requests.size == 2) nextRequest.complete(Unit)
            created(48000) + delta + done
        })
        val input = kotlinx.coroutines.channels.Channel<String>(2).apply {
            trySend("第一句。"); trySend("第二句。"); close()
        }
        val rates = mutableListOf<Int>()
        val audio = mutableListOf<Byte>()
        withTimeout(3000) {
            SentenceSpeech(cloud::speak).speak(input, rates::add) {
                nextRequest.await()
                audio.addAll(it.toList())
            }
        }
        assertEquals(listOf("第一句。", "第二句。"), requests.toList())
        assertEquals(listOf(48000), rates)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 1, 2, 3, 4), audio)
    }

    private fun config() = SpeechConfig.openAiDefaults().copy(sttKey = "asr-key")
        .withTtsProvider("mossland").copy(ttsKey = "moss-test-key", voice = "voice-id")

    private fun client(handler: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request()).toResponseBody("text/event-stream".toMediaType())).build()
    }.build()

    private fun created(rate: Int = 24000) = "data: {\"type\":\"speech.created\",\"format\":\"pcm\",\"sample_rate\":$rate,\"channels\":1,\"bit_depth\":16}\n\n"
    private val delta = "data: {\"type\":\"speech.audio.delta\",\"audio\":\"AQIDBA==\"}\n\n"
    private val done = "data: {\"type\":\"speech.audio.done\"}\n\n"

    @Test fun requestsMossSseAndPlaysPcmWithoutWaitingForAnAudioFile() = runBlocking {
        val bytes = mutableListOf<Byte>()
        CloudSpeech(config(), client { request ->
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("https://api.mosi.cn/v1/audio/speech", request.url.toString())
            assertEquals("Bearer moss-test-key", request.header("Authorization"))
            assertEquals("voice-id", json["voice_id"]?.asString)
            assertEquals(true, json["stream"]?.asBoolean)
            assertEquals("sse", json["stream_format"]?.asString)
            assertFalse(json.has("sample_rate"))
            assertFalse(json.has("voice"))
            created() + delta + done
        }).speak("你好") { bytes.addAll(it.toList()) }
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes)
    }

    @Test fun formatIsDeliveredBeforeSamplesAndOddNetworkBoundariesRemainOrdered() = runBlocking {
        val observations = mutableListOf<String>()
        val pcm = mutableListOf<Byte>()
        val stream = ": heartbeat\n\ndata: {\"type\":\"task.created\"}\n\n" + created(48000) +
            "data: {\"type\":\"speech.audio.delta\",\"audio\":\"AQ==\"}\n\n" +
            "data: {\"type\":\"speech.audio.delta\",\"audio\":\"\"}\n\n" +
            "data: {\"type\":\"speech.audio.delta\",\"audio\":\"AgME\"}\n\n" + done
        CloudSpeech(config(), client { stream }).speak("hello", { observations += "rate:$it" }) {
            observations += "pcm"
            pcm.addAll(it.toList())
        }
        assertEquals(listOf("rate:48000", "pcm"), observations)
        assertEquals(listOf<Byte>(1, 2, 3, 4), pcm)
    }

    @Test fun missingCompletionMalformedEventsAndInvalidFormatsFailWithoutLeakingServiceText() {
        val streams = listOf(
            created() + delta,
            created() + done,
            delta + done,
            created().replace("\"channels\":1", "\"channels\":2") + delta + done,
            created().replace("\"bit_depth\":16", "\"bit_depth\":32") + delta + done,
            created(0) + delta + done,
            created() + created() + delta + done,
            created() + "data: {\"type\":\"speech.audio.delta\",\"audio\":\"AQ==\"}\n\n" + done,
            created() + "data: {\"type\":\"speech.audio.delta\",\"audio\":\"%%%\"}\n\n" + done,
            "data: {\"type\":\"error\",\"error\":{\"message\":\"moss-test-key private text\"}}\n\n",
            "data: {private-text}\n\n",
        )
        for (stream in streams) {
            val error = assertThrows(SpeechApiException::class.java) {
                runBlocking { CloudSpeech(config(), client { stream }).speak("hello", {}, {}) }
            }
            assertFalse(error.message.orEmpty().contains("moss-test-key"))
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test fun emitsFirstPcmWhileResponseIsStillOpenAndCancellationClosesIt() = runBlocking {
        val firstPcm = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val waitingForMore = CountDownLatch(1)
        val finishedRead = CountDownLatch(1)
        val prefix = Buffer().writeUtf8(created(48000) + delta)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (prefix.size > 0) return prefix.read(sink, byteCount)
                try {
                    waitingForMore.countDown()
                    if (!closed.await(3, TimeUnit.SECONDS)) throw java.io.IOException("test wait timed out")
                    // A late event after cancellation must not reach the player.
                    return sink.writeUtf8(delta + done).let { (delta + done).toByteArray().size.toLong() }
                } finally { finishedRead.countDown() }
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build()
        var chunks = 0
        val job = launch { CloudSpeech(config(), http).speak("hello", {}) { chunks++; firstPcm.complete(Unit) } }
        withTimeout(2000) { firstPcm.await() }
        assertTrue(waitingForMore.await(1, TimeUnit.SECONDS))
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        assertTrue(finishedRead.await(1, TimeUnit.SECONDS))
        assertEquals(1, chunks)
        val fresh = mutableListOf<Byte>()
        CloudSpeech(config(), client { created() + delta + done }).speak("next") { fresh.addAll(it.toList()) }
        assertEquals(listOf<Byte>(1, 2, 3, 4), fresh)
    }

    @Test fun oversizedSseEventFailsAndDoesNotReachAudio() {
        val huge = "data: " + "x".repeat(1_048_577) + "\n\n"
        assertThrows(SpeechApiException::class.java) {
            runBlocking { CloudSpeech(config(), client { huge }).speak("hello") { fail("invalid audio") } }
        }
    }

    @Test fun mossSelectionPreservesRecognitionAndPersistsWithoutEnablingBidi() {
        val old = SpeechConfig.bailianDefaults().copy(sttKey = "asr-key", ttsKey = "old-tts", voice = "old-voice")
        val selected = old.withTtsProvider("mossland")
        assertEquals(old.sttKey, selected.sttKey)
        assertEquals(old.sttBaseUrl, selected.sttBaseUrl)
        assertEquals("https://api.mosi.cn/v1/audio/speech", selected.ttsEndpoint().toString())
        assertEquals("moss-tts-1.5-flash", selected.ttsModel)
        assertEquals("", selected.ttsKey)
        assertEquals("", selected.voice)
        assertFalse(selected.streamingTts)
        val configured = selected.copy(ttsKey = "moss-test-key", voice = "voice-id")
        configured.validate()
        assertEquals(configured, Gson().fromJson(Gson().toJson(configured), SpeechConfig::class.java))
    }
}
