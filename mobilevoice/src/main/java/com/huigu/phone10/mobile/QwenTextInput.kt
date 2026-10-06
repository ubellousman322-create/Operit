package com.huigu.phone10.mobile

/** Channel fragments are not sentence boundaries. Keep punctuation and Unicode intact. */
internal class QwenTextInput {
    private val pending = StringBuilder()
    private var surrogate = ""
    private var total = 0

    fun push(fragment: String): List<String> {
        var text = surrogate + fragment
        surrogate = ""
        if (text.lastOrNull()?.isHighSurrogate() == true) {
            surrogate = text.takeLast(1)
            text = text.dropLast(1)
        }
        val output = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            require(cp !in 0xD800..0xDFFF) { "Invalid Unicode" }
            val count = Character.charCount(cp)
            // Use a conservative UTF-16 chunk ceiling; never split a surrogate pair.
            if (pending.length + count > 4095) emit(output)
            pending.appendCodePoint(cp)
            offset += count
            if ((cp <= 0xFFFF && cp.toChar() in "。！？!?\n") || englishBoundary(cp)) {
                if (cp != '\n'.code) pending.append('\n')
                emit(output)
            }
        }
        return output
    }

    private fun englishBoundary(cp: Int): Boolean {
        if (!Character.isWhitespace(cp)) return false
        val tail = pending.toString().trimEnd()
        if (!tail.endsWith('.')) return false
        val word = tail.substringAfterLast(' ').lowercase()
        return word !in ABBREVIATIONS && !INITIALS.matches(word)
    }

    fun finish(): List<String> {
        require(surrogate.isEmpty()) { "Incomplete Unicode" }
        return mutableListOf<String>().also(::emit)
    }

    private fun emit(output: MutableList<String>) {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.setLength(0)
        if (text.isBlank()) return
        total += text.codePointCount(0, text.length)
        if (total > 20_000) throw SpeechApiException("本机语音 单条回复超过 20000 字符，语音已停止；完整文字仍在聊天中。")
        output.add(text)
    }

    private companion object {
        val ABBREVIATIONS = setOf("mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "vs.", "etc.", "e.g.", "i.e.")
        val INITIALS = Regex("(?:[a-z]\\.)+")
    }
}
