package com.huigu.phone10.mobile

/** Call-local display text only; the existing chat frontend owns history. */
internal class CaptionBuffer(private val limit: Int = 6000) {
    var text: String = ""
        private set

    init { require(limit > 1) }

    fun clear() { text = "" }
    fun append(delta: String) {
        val joined = (text + delta).takeLast(limit)
        text = if (joined.firstOrNull()?.isLowSurrogate() == true) joined.drop(1) else joined
    }
}
