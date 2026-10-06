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
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue

class VolcengineRecognitionTest {
    @Test fun protocolChecksTerminalFlagsTruncationAndProviderErrors() {
        assertTrue(VolcengineRecognitionProtocol.decode(reply("尾句", -3)).finished)
        assertFalse(VolcengineRecognitionProtocol.decode(reply("未结束", 2)).finished)
        val withoutSequence = ByteBuffer.allocate(10).apply {
            put(0x11); put(0x92.toByte()); put(0x10); put(0); putInt(2); put("{}".toByteArray())
        }.array().toByteString()
        assertTrue(VolcengineRecognitionProtocol.decode(withoutSequence).finished)
        val valid = reply("尾句", -3).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { VolcengineRecognitionProtocol.decode(valid.copyOf(valid.size - 1).toByteString()) }
        assertThrows(IllegalArgumentException::class.java) {
            VolcengineRecognitionProtocol.decode(valid.copyOf().apply { this[0] = 0x21 }.toByteString())
        }
        val error = ByteBuffer.allocate(14).apply {
            put(0x11); put(0xf0.toByte()); put(0x10); put(0); putInt(45000001); putInt(2); put("{}".toByteArray())
        }.array().toByteString()
        assertEquals(45000001, VolcengineRecognitionProtocol.decode(error).error)
    }

    @Test fun blankFinalPassesConnectionCheckAndPrematureCloseFailsSafely() = runBlocking {
        for (blank in listOf(true, false)) {
            val server = MockWebServer()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (bytes[1].toInt() and 2 != 0) {
                        if (blank) ws.send(reply("", -2))
                        else { ws.send(reply("只有部分", 1)); ws.close(1000, "private-server-reason") }
                    }
                }
            }))
            server.start(); val client = OkHttpClient()
            val factory = object : WebSocket.Factory {
                override fun newWebSocket(request: Request, listener: WebSocketListener) =
                    client.newWebSocket(request.newBuilder().url(server.url("/asr")).build(), listener)
            }
            try {
                if (blank) withTimeout(5000) { CloudSpeech(config(), client, factory).checkRecognitionConnection() }
                else {
                    val failure = assertThrows(SpeechApiException::class.java) {
                        runBlocking { withTimeout(5000) { CloudSpeech(config(), client, factory).transcribe(byteArrayOf(1, 2)) } }
                    }
                    assertFalse(failure.message.orEmpty().contains("private-server-reason"))
                }
            } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.shutdown(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    private fun config() = SpeechConfig.openAiDefaults().copy(provider = "volcengine", ttsProvider = "openai",
        sttBaseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async", sttModel = "bigmodel",
        sttKey = "RECOGNITION-ONLY", ttsKey = "SYNTHESIS-ONLY")

    // Independent protocol fixture, not the production encoder.
    private fun reply(text: String, sequence: Int, compressed: Boolean = false): ByteString {
        val raw = "{\"result\":{\"text\":\"$text\"}}".toByteArray()
        val payload = if (compressed) ByteArrayOutputStream().also { output ->
            java.util.zip.GZIPOutputStream(output).use { it.write(raw) }
        }.toByteArray() else raw
        return ByteBuffer.allocate(12 + payload.size).apply {
            put(0x11); put(if (sequence < 0) 0x93.toByte() else 0x91.toByte()); put(if (compressed) 0x11 else 0x10); put(0)
            putInt(sequence); putInt(payload.size); put(payload)
        }.array().toByteString()
    }

    @Test fun uploadsPcmAndWaitsForCorrectedFinalTextWithSeparateAuthentication() = runBlocking {
        val uploaded = Channel<WebSocket>(1)
        val audio = ByteArrayOutputStream()
        val headers = LinkedBlockingQueue<Request>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val b = ByteBuffer.wrap(bytes.toByteArray())
                assertEquals(0x11.toByte(), b.get())
                val kind = b.get().toInt() and 255
                val encoding = b.get().toInt() and 255; b.get()
                assertEquals(b.remaining() - 4, b.int)
                val payload = ByteArray(b.remaining()).also { b.get(it) }
                when (kind ushr 4) {
                    1 -> {
                        assertEquals(0x10, encoding)
                        val json = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
                        assertEquals("bigmodel", json.getAsJsonObject("request")["model_name"].asString)
                        assertEquals(16000, json.getAsJsonObject("audio")["rate"].asInt)
                        assertEquals("pcm", json.getAsJsonObject("audio")["format"].asString)
                        assertFalse(payload.toString(Charsets.UTF_8).contains("SYNTHESIS-ONLY"))
                    }
                    2 -> {
                        assertEquals(0, encoding); audio.write(payload)
                        if (kind and 2 != 0) {
                            ws.send(reply("你号", 1)); uploaded.trySend(ws)
                        }
                    }
                }
            }
        }))
        server.start(); val client = OkHttpClient()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                headers.add(request)
                return client.newWebSocket(request.newBuilder().url(server.url("/asr")).build(), listener)
            }
        }
        try {
            val pcm = ByteArray(6402) { (it % 120).toByte() }
            val task = async { CloudSpeech(config(), client, factory).transcribe(pcm) }
            val ws = withTimeout(5000) { uploaded.receive() }
            assertFalse(task.isCompleted)
            assertArrayEquals(pcm, audio.toByteArray())
            assertEquals("RECOGNITION-ONLY", headers.single().header("X-Api-Key"))
            assertEquals("volc.seedasr.sauc.duration", headers.single().header("X-Api-Resource-Id"))
            assertNull(headers.single().header("Authorization"))
            ws.send(reply("你好", -2, compressed = true))
            assertEquals("你好", withTimeout(5000) { task.await() })
        } finally { uploaded.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.shutdown(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun cancellationClosesSocketAndIncompleteResultsNeverBecomeUserMessages() = runBlocking {
        val received = Channel<Unit>(1); val disconnected = Channel<Unit>(1)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (bytes[1].toInt() and 2 != 0) { ws.send(reply("未完成", 1)); received.trySend(Unit) }
            }
            override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) { disconnected.trySend(Unit) }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { disconnected.trySend(Unit) }
        }))
        server.start(); val client = OkHttpClient()
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener) =
                client.newWebSocket(request.newBuilder().url(server.url("/asr")).build(), listener)
        }
        try {
            val task = launch { CloudSpeech(config(), client, factory).transcribe(byteArrayOf(1, 2)); fail("canceled result escaped") }
            withTimeout(5000) { received.receive() }
            withTimeout(5000) { task.cancelAndJoin() }
            withTimeout(5000) { disconnected.receive() }
        } finally { received.cancel(); disconnected.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.shutdown(); client.dispatcher.executorService.shutdownNow() }
    }
}
