package com.huigu.phone10.mobile

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RecognitionProvidersTest {
    @Test fun changingRecognitionKeepsLegacyVoiceAndRestoresIndependentSavedCredentials() {
        val original = SpeechConfig.bailianDefaults().copy(sttKey = "OLD-ASR", ttsKey = "KEEP-TTS", voice = "voice")
        val volc = original.withSttProvider("volcengine").copy(sttKey = "VOLC-ASR", sttVolcAppId = "123",
            sttVolcResource = "volc.bigasr.sauc.concurrent", volcAppId = "456", volcResource = "seed-icl-2.0")
        volc.validate()
        val header = VolcengineRecognition(volc, OkHttpClient()).request()
        assertEquals("123", header.header("X-Api-App-Key"))
        assertEquals("VOLC-ASR", header.header("X-Api-Access-Key"))
        assertEquals("volc.bigasr.sauc.concurrent", header.header("X-Api-Resource-Id"))
        assertNull(header.header("X-Api-Key"))
        val moss = volc.withSttProvider("mossland").copy(sttKey = "MOSS-ASR")
        val returned = moss.withSttProvider("volcengine", volc)
        assertEquals("VOLC-ASR", returned.sttKey)
        assertEquals("123", returned.sttVolcAppId)
        assertEquals("bailian", returned.effectiveTtsProvider)
        assertEquals(original.ttsBaseUrl, returned.ttsBaseUrl)
        assertEquals("KEEP-TTS", returned.ttsKey)
        assertEquals(original.voice, returned.voice)
        val saved = MobileSettings(returned).saveVoiceProfile("识别独立")
        val gson = Gson()
        val loaded = gson.fromJson(gson.toJson(saved), MobileSettings::class.java)
        assertEquals(returned, loaded.selectVoiceProfile(loaded.profiles().single().id).speech)
        assertFalse(loaded.toString().contains("VOLC-ASR"))
    }

    @Test fun invalidResourcesAndRecognitionAddressesAreRejectedBeforeSending() {
        val base = SpeechConfig.openAiDefaults().copy(sttKey = "ASR", ttsKey = "TTS")
        for (config in listOf(
            base.withSttProvider("volcengine").copy(sttKey = "ASR", sttVolcResource = "seed-icl-2.0"),
            base.withSttProvider("volcengine").copy(sttKey = "ASR", sttBaseUrl = "wss://host/api/v3/tts/bidirection"),
            base.withSttProvider("volcengine").copy(sttKey = "ASR", sttVolcAppId = "bad\r\nheader"),
            base.withSttProvider("minimax").copy(sttKey = "ASR", sttModel = "speech-2.8-turbo"),
            base.withSttProvider("mossland").copy(sttKey = "ASR", sttModel = "moss-tts-1.5-flash")
        )) assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test fun mossAndMinimaxUseTheirDocumentedUploadEndpointsAndOnlyRecognitionKey() = runBlocking {
        for ((provider, url, model) in listOf(
            Triple("mossland", "https://api.mosi.cn/v1/audio/transcriptions", "moss-transcribe-1.0"),
            Triple("minimax", "https://api.minimaxi.com/v1/speech_to_text", "asr-1.0"))) {
            val base = url.substringBeforeLast('/') .let { if (provider == "mossland") it.removeSuffix("/audio") else it }
            val config = SpeechConfig.openAiDefaults().copy(provider = provider, sttBaseUrl = base,
                sttKey = "ASR-ONLY", sttModel = model, ttsProvider = "openai", ttsKey = "TTS-ONLY")
            var calls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals(url, request.url.toString())
                assertEquals("Bearer ASR-ONLY", request.header("Authorization"))
                val bytes = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
                val body = bytes.toString(Charsets.ISO_8859_1)
                assertTrue(body.contains("name=\"model\"\r\n\r\n$model"))
                assertTrue(body.contains("name=\"response_format\"\r\n\r\njson"))
                val start = body.indexOf("RIFF"); assertTrue(start >= 0)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(start + 44, start + 48))
                assertFalse(body.contains("TTS-ONLY"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"text\":\" 你好 \"}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            assertEquals("你好", CloudSpeech(config, http).transcribe(byteArrayOf(1, 2, 3, 4)))
            assertEquals(1, calls)
        }
    }
}
