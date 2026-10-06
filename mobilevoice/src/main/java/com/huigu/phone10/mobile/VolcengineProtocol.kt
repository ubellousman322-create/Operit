package com.huigu.phone10.mobile

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** V3 event protocol. Integer fields and lengths are big-endian; PCM remains raw. */
internal object VolcengineProtocol {
    data class Frame(val type: Int, val event: Int, val session: String, val payload: ByteArray, val error: Int? = null)
    private const val MAX = 4_194_304

    fun encode(event: Int, session: String = "", json: String = "{}"): ByteString {
        val identity = session.toByteArray(Charsets.UTF_8)
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX && identity.size <= 1024)
        return ByteBuffer.allocate(12 + payload.size + if (event >= 100) 4 + identity.size else 0).apply {
            put(0x11); put(0x14); put(0x10); put(0); putInt(event)
            if (event >= 100) { putInt(identity.size); put(identity) }
            putInt(payload.size); put(payload)
        }.array().toByteString()
    }

    fun decode(bytes: ByteString): Frame {
        require(bytes.size in 8..MAX)
        val b = ByteBuffer.wrap(bytes.toByteArray())
        val first = b.get().toInt() and 255
        require(first ushr 4 == 1)
        val header = (first and 15) * 4
        require(header in 4..bytes.size)
        val typeFlags = b.get().toInt() and 255
        val encoding = b.get().toInt() and 255
        val type = typeFlags ushr 4
        val flags = typeFlags and 15
        val serialization = encoding ushr 4
        val compression = encoding and 15
        require(type in setOf(9, 11, 15) && compression in 0..1)
        require(serialization == if (type == 11) 0 else 1)
        b.position(header)
        fun part(max: Int): ByteArray {
            require(b.remaining() >= 4)
            val n = b.int
            require(n in 0..max && n <= b.remaining())
            return ByteArray(n).also { b.get(it) }
        }
        val error = if (type == 15) { require(b.remaining() >= 4); b.int } else null
        var event = 0; var session = ""
        if (type != 15) {
            require(flags == 4 && b.remaining() >= 4)
            event = b.int
            // Connection events carry connection ID; session events carry session ID.
            session = part(1024).toString(Charsets.UTF_8)
        }
        var payload = part(MAX)
        require(!b.hasRemaining())
        if (compression == 1) {
            payload = GZIPInputStream(payload.inputStream()).use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= MAX); output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        return Frame(type, event, session, payload, error)
    }
}
