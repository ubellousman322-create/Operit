package com.huigu.phone10.mobile

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal data class LocalVoice(val id: String, val name: String, val model: String,
    val ready: Boolean, val supportedModes: Set<String>) {
    companion object {
        fun parseCatalog(raw: String): List<LocalVoice> = try {
            val rows = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("voices")
            require(rows.size() <= 200)
            val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
            rows.map { row ->
                val obj = row.asJsonObject
                fun string(key: String): String {
                    val value = obj.getAsJsonPrimitive(key)
                    require(value.isString)
                    return value.asString
                }
                val id = string("id"); val model = string("model"); val name = string("name")
                require(identifier.matches(id) && identifier.matches(model))
                require(name.isNotBlank() && name.length <= 160 && name.none { it.isISOControl() })
                val ready = obj.getAsJsonPrimitive("ready").also { require(it.isBoolean) }.asBoolean
                val modes = obj.get("supported_modes")?.let { value ->
                    require(value.isJsonArray && value.asJsonArray.size() <= 16)
                    value.asJsonArray.map { mode ->
                        require(mode.isJsonPrimitive && mode.asJsonPrimitive.isString)
                        mode.asString.also { require(identifier.matches(it)) }
                    }.toSet()
                }.orEmpty()
                LocalVoice(id, name, model, ready, modes)
            }.also { entries -> require(entries.map { it.model to it.id }.distinct().size == entries.size) }
        } catch (_: Exception) { throw SpeechApiException("本机声音列表格式无效，已保存的方案保持不变。") }
    }
}

internal fun requireLocalVoice(voices: List<LocalVoice>, config: SpeechConfig): LocalVoice {
    val voice = voices.singleOrNull { it.model == config.ttsModel && it.id == config.voice }
        ?: throw SpeechApiException("电脑端没有这个模型和音色，请刷新声音列表后重新选择。")
    if (!voice.ready) throw SpeechApiException("这个声音当前不可用，请先在电脑端开启并准备好。")
    val mode = if (config.clientSegmentedTts) "none" else config.effectiveQwenSplitGranularity
    if (mode !in voice.supportedModes)
        throw SpeechApiException("这个声音不支持所选生成方式，请按电脑端支持的方式保存。")
    return voice
}

private val localVoiceHttp by lazy {
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()
}

internal suspend fun fetchLocalVoices(config: SpeechConfig): List<LocalVoice> =
    QwenLifecycle(config, localVoiceHttp).voices()

internal fun MobileSettings.localVoiceConnection(): SpeechConfig? =
    speech.takeIf { it.effectiveTtsProvider == SpeechConfig.QWEN_LOCAL }
        ?: profiles().firstOrNull { it.speech.effectiveTtsProvider == SpeechConfig.QWEN_LOCAL }?.speech

internal fun MobileSettings.addLocalVoice(base: SpeechConfig, voice: LocalVoice): MobileSettings {
    val mode = when {
        "none" in voice.supportedModes -> "client_segments"
        "sentence" in voice.supportedModes -> "sentence"
        else -> throw SpeechApiException("这个声音尚未提供可用的生成方式。")
    }
    val nextSpeech = speech.copy(ttsProvider = SpeechConfig.QWEN_LOCAL,
        ttsBaseUrl = base.ttsBaseUrl, ttsKey = base.ttsKey,
        ttsModel = voice.model, voice = voice.id, qwenSplitGranularity = mode)
    requireLocalVoice(listOf(voice), nextSpeech)
    val existing = profiles().firstOrNull {
        it.speech.effectiveTtsProvider == SpeechConfig.QWEN_LOCAL && it.speech.ttsBaseUrl == base.ttsBaseUrl &&
            it.speech.ttsKey == base.ttsKey && it.speech.ttsModel == voice.model && it.speech.voice == voice.id
    }
    val next = copy(speech = nextSpeech, voiceName = voice.name)
    if (existing != null) return next.copy(activeVoiceProfileId = existing.id)
    val prefix = voice.name.take(32)
    val title = if (profiles().none { it.name == prefix }) prefix else
        (2..21).map { "$prefix ($it)" }.first { title -> profiles().none { it.name == title } }
    return next.saveVoiceProfile(title)
}
