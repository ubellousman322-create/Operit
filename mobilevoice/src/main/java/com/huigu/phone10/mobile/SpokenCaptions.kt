package com.huigu.phone10.mobile

/** Uses exactly the cleaned segments accepted by the existing speech queue. Main-thread owned. */
internal class SpokenCaptions {
    private val content = StringBuilder()
    private val ranges = ArrayList<IntRange>()
    private var cached: String? = ""
    val text: String get() = cached ?: content.toString().also { cached = it }
    fun append(text: String) {
        if (content.isNotEmpty()) content.append("\n\n")
        val start = content.length
        content.append(text)
        ranges.add(start until content.length)
        cached = null
    }
    fun range(segment: Int?): IntRange? = segment?.let { ranges.getOrNull(it) }?.takeUnless { it.isEmpty() }
    fun clear() { content.clear(); ranges.clear(); cached = "" }
}

internal class CaptionFollow {
    var enabled = true
        private set
    fun userScrolled() { enabled = false }
    fun resume() { enabled = true }
    fun reset() { enabled = true }
}
