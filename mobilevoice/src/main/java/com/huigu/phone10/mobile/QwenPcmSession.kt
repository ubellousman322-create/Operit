package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Verifies one response before allowing a successful completion. Never logs response bodies. */
internal class QwenPcmSession {
    private var active = false
    private var sentence = 0L
    private var bytes = 0L
    private var totalBytes = 0L

    fun pcm(data: ByteArray) {
        require(active && data.isNotEmpty() && data.size % 2 == 0)
        bytes += data.size
        totalBytes += data.size
        require(totalBytes <= 86_400_000) // 30 minutes at the fixed PCM rate.
    }

    fun event(raw: String, inputFinished: Boolean): Boolean {
        val json = JsonParser.parseString(raw).asJsonObject
        val type = json["type"].asString
        if (type == "error") throw SpeechApiException("本机语音 合成失败，请检查电脑端语音状态；本轮不会自动重播。")
        require(integer(json, "utterance_index") == 0L)
        when (type) {
            "audio.start" -> {
                require(!active && integer(json, "sentence_index") == sentence)
                require(json["format"].asString == "pcm" && integer(json, "sample_rate") == 24_000L)
                json["channels"]?.let { require(integer(json, "channels") == 1L) }
                active = true; bytes = 0
            }
            "audio.done" -> {
                require(active && integer(json, "sentence_index") == sentence)
                json["error"]?.let {
                    val noError = it.isJsonNull || (it.isJsonPrimitive &&
                        ((it.asJsonPrimitive.isBoolean && !it.asBoolean) ||
                            (it.asJsonPrimitive.isString && it.asString.isEmpty())))
                    if (!noError)
                        throw SpeechApiException("本机语音 本句合成失败，语音未完整返回。")
                }
                require(bytes > 0 && integer(json, "total_bytes") == bytes)
                active = false; sentence++
            }
            "session.done" -> {
                require(inputFinished && !active && sentence > 0 && integer(json, "total_sentences") == sentence)
                return true
            }
            else -> error("Unknown Qwen event")
        }
        return false
    }

    private fun integer(json: JsonObject, name: String): Long {
        val value = json[name].asJsonPrimitive
        require(value.isNumber)
        return value.asBigDecimal.longValueExact()
    }
}
