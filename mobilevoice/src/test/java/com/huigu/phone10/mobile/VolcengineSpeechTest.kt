package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class VolcengineSpeechTest {
    @Test fun modelChoicesAndPromptStayInEncryptedProfilePayload() {
        val client = OkHttpClient()
        val settings = MobileSettings(config().copy(voicePrompt = "自然轻声一点", volcAppId = "1234"))
            .saveVoiceProfile("火山")
        val gson = com.google.gson.Gson()
        val restored = gson.fromJson(gson.toJson(settings), MobileSettings::class.java)
        assertEquals(settings, restored)
        val speech = VolcengineSpeech(restored.speech, client)
        val header = speech.request()
        assertNull(header.header("X-Api-Key"))
        assertEquals("VOLC-ONLY", header.header("X-Api-Access-Key"))
        assertEquals("1234", header.header("X-Api-App-Id"))
        val parameters = JsonParser.parseString(speech.payload(100)).asJsonObject.getAsJsonObject("req_params")
        assertEquals("seed-tts-2.0-expressive", parameters["model"].asString)
        val additions = JsonParser.parseString(parameters["additions"].asString).asJsonObject
        assertEquals("自然轻声一点", additions.getAsJsonArray("context_texts")[0].asString)
        for (resource in SpeechConfig.VOLC_RESOURCES) {
            val c = config().copy(volcResource = resource, voicePrompt = "自然轻声一点")
            c.validate()
            val p = JsonParser.parseString(VolcengineSpeech(c, client).payload(100)).asJsonObject.getAsJsonObject("req_params")
            assertEquals(resource == "seed-icl-2.0", p.has("model"))
            assertEquals(resource in setOf("seed-icl-2.0", "seed-tts-2.0"), p.has("additions"))
        }
        val standard = config().copy(ttsModel = "seed-tts-2.0-standard", voicePrompt = "自然轻声一点")
        assertFalse(JsonParser.parseString(VolcengineSpeech(standard, client).payload(100)).asJsonObject.getAsJsonObject("req_params").has("additions"))
        assertFalse(settings.toString().contains("VOLC-ONLY"))
    }

    @Test fun binaryProtocolMatchesDocumentAndRejectsTruncatedPayloads() {
        assertEquals("1114100000000001000000027b7d", VolcengineProtocol.encode(1).hex())
        val frame = response(352, "session", byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), VolcengineProtocol.decode(frame).payload)
        val broken = frame.toByteArray().copyOf(frame.size - 1).toByteString()
        assertThrows(IllegalArgumentException::class.java) { VolcengineProtocol.decode(broken) }
        val invalid = frame.toByteArray().apply { this[0] = 0x21 }.toByteString()
        assertThrows(IllegalArgumentException::class.java) { VolcengineProtocol.decode(invalid) }
        val error = ByteBuffer.allocate(14).apply {
            put(0x11); put(0xf0.toByte()); put(0x10); put(0); putInt(45000001); putInt(2); put("{}".toByteArray())
        }.array().toByteString()
        assertEquals(45000001, VolcengineProtocol.decode(error).error)
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write(byteArrayOf(3, 4, 5, 6)) }
        val compressed = response(352, "session", output.toByteArray()).toByteArray().apply { this[2] = 1 }.toByteString()
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), VolcengineProtocol.decode(compressed).payload)
    }

    @Test fun cancelStopsOldAudioAndNextReplyOwnsANewSession() = runBlocking {
        val cancellations = Channel<String>(1)
        val ids = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        repeat(2) { turn -> server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val r = request(bytes)
                when (r.first) {
                    1 -> ws.send(response(50, "connection"))
                    100 -> { ids.add(r.second); ws.send(response(150, r.second)) }
                    200 -> ws.send(response(352, r.second, byteArrayOf((turn + 1).toByte(), 0)))
                    101 -> {
                        cancellations.trySend(r.second)
                        ws.send(response(151, r.second))
                        ws.send(response(352, r.second, byteArrayOf(99, 0)))
                    }
                    102 -> ws.send(response(152, r.second))
                }
            }
        })) }
        server.start()
        val client = OkHttpClient()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener) =
                client.newWebSocket(request.newBuilder().url(server.url("/api/v3/tts/bidirection")).build(), listener)
        }
        val first = Channel<String>(1); val next = Channel<String>(1); val audio = Channel<ByteArray>(4)
        try {
            first.send("未结束的第一轮")
            val job = launch { VolcengineSpeech(config(), factory).speakStream(first) { audio.trySend(it) } }
            assertArrayEquals(byteArrayOf(1, 0), withTimeout(5000) { audio.receive() })
            withTimeout(5000) { job.cancelAndJoin() }
            assertEquals(ids.first(), withTimeout(5000) { cancellations.receive() })
            assertTrue(audio.tryReceive().isFailure)
            next.send("新的第二轮"); next.close()
            withTimeout(5000) { VolcengineSpeech(config(), factory).speakStream(next) { audio.trySend(it) } }
            assertArrayEquals(byteArrayOf(2, 0), audio.receive())
            assertEquals(2, ids.distinct().size)
        } finally {
            first.cancel(); next.cancel(); audio.cancel(); cancellations.cancel()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun config() = SpeechConfig.bailianDefaults().copy(sttKey = "ASR-ONLY", ttsKey = "VOLC-ONLY",
        ttsProvider = "volcengine", ttsBaseUrl = "wss://openspeech.bytedance.com/api/v3/tts/bidirection",
        ttsModel = "seed-tts-2.0-expressive", voice = "speaker-test")

    // Independent server fixture: v1 + event flag + big-endian length-prefixed fields.
    private fun response(event: Int, id: String, audio: ByteArray? = null): ByteString {
        val payload = audio ?: "{}".toByteArray()
        val identity = id.toByteArray()
        return ByteBuffer.allocate(16 + identity.size + payload.size).apply {
            put(0x11); put(if (audio == null) 0x94.toByte() else 0xb4.toByte())
            put(if (audio == null) 0x10 else 0); put(0); putInt(event)
            putInt(identity.size); put(identity); putInt(payload.size); put(payload)
        }.array().toByteString()
    }

    private fun request(bytes: ByteString): Triple<Int, String, String> {
        val b = ByteBuffer.wrap(bytes.toByteArray()); b.position(4)
        val event = b.int
        fun string(): String { val result = ByteArray(b.int); b.get(result); return result.toString(Charsets.UTF_8) }
        val id = if (event >= 100) string() else ""
        return Triple(event, id, string())
    }

    @Test fun streamsPcmBeforeInputEndsAndFinishesOnlyAfterSessionFinished() = runBlocking {
        val messages = LinkedBlockingQueue<Triple<Int, String, String>>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            var parts = 0
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val r = request(bytes); messages.add(r)
                when (r.first) {
                    1 -> ws.send(response(50, "connection"))
                    100 -> ws.send(response(150, r.second))
                    200 -> {
                        ws.send(response(352, r.second, if (parts++ == 0) byteArrayOf(1, 2, 3) else byteArrayOf(4)))
                        ws.send(response(351, r.second))
                    }
                    102 -> ws.send(response(152, r.second))
                }
            }
        }))
        server.start()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val captured = LinkedBlockingQueue<Request>()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(r: Request, listener: WebSocketListener): WebSocket {
                captured.add(r)
                val delegate = client.newWebSocket(r.newBuilder().url(server.url("/api/v3/tts/bidirection")).build(), listener)
                return object : WebSocket by delegate {
                    // The service may close after completing the session. A failed
                    // connection-cleanup send must not discard a complete reply.
                    override fun send(bytes: ByteString): Boolean =
                        if (request(bytes).first == 2) false else delegate.send(bytes)
                }
            }
        }
        val texts = Channel<String>(2); val audio = Channel<ByteArray>(2)
        try {
            texts.send("先说这句，")
            val task = async { CloudSpeech(config(), client, factory).speakStream(texts) { audio.trySend(it) } }
            assertArrayEquals(byteArrayOf(1, 2), withTimeout(5000) { audio.receive() })
            assertFalse(task.isCompleted)
            texts.send("再说尾句。")
            assertArrayEquals(byteArrayOf(3, 4), withTimeout(5000) { audio.receive() })
            texts.close(); withTimeout(5000) { task.await() }
            assertEquals(listOf(1, 100, 200, 200, 102), messages.map { it.first }.filter { it != 2 })
            assertEquals(1, messages.filter { it.first >= 100 }.map { it.second }.distinct().size)
            val start = JsonParser.parseString(messages.first { it.first == 100 }.third).asJsonObject.getAsJsonObject("req_params")
            assertEquals("pcm", start.getAsJsonObject("audio_params")["format"].asString)
            assertEquals(24000, start.getAsJsonObject("audio_params")["sample_rate"].asInt)
            assertEquals("speaker-test", start["speaker"].asString)
            assertEquals("VOLC-ONLY", captured.single().header("X-Api-Key"))
            assertEquals("seed-icl-2.0", captured.single().header("X-Api-Resource-Id"))
            assertFalse(messages.toString().contains("ASR-ONLY"))
        } finally {
            texts.cancel(); audio.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            server.shutdown(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
