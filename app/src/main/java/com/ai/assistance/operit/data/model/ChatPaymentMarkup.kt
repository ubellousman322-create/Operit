package com.ai.assistance.operit.data.model

import java.util.Locale

/**
 * 聊天里的红包 / 转账卡片。
 *
 * 消息正文用自闭合标签携带完整信息，这样历史记录、复制、导出、别的设备看到的都是同一句话，
 * 渲染层只负责把它画成卡片：
 *
 *   <hongbao amount="5.20" greeting="拿去喝点热的" sender="ave" from="ai"/>
 *   <transfer amount="52.00" note="乖" sender="ave" from="ai"/>
 *
 * 红包/转账后面如果还跟了一段正文（"发红包时带消息"），那一段会作为 [trailingText]
 * 挂在卡片下面一起显示。
 */
sealed class ChatPayment {
    abstract val amount: String
    abstract val from: String
    abstract val senderName: String
    abstract val trailingText: String

    val isFromUser: Boolean get() = from.equals(FROM_USER, ignoreCase = true)

    /** 红包。greeting 是红包封面上的那句祝福语。 */
    data class RedPacket(
            override val amount: String,
            val greeting: String,
            override val from: String = FROM_USER,
            override val senderName: String = "",
            override val trailingText: String = "",
    ) : ChatPayment()

    /** 转账。note 是转账说明。 */
    data class Transfer(
            override val amount: String,
            val note: String,
            override val from: String = FROM_USER,
            override val senderName: String = "",
            override val trailingText: String = "",
    ) : ChatPayment()

    companion object {
        const val FROM_USER = "user"
        const val FROM_AI = "ai"
    }
}

object ChatPaymentMarkup {

    private const val TAG_RED_PACKET = "hongbao"
    private const val TAG_TRANSFER = "transfer"

    const val DEFAULT_GREETING = "恭喜发财，大吉大利"

    private val tagRegex =
            Regex("<([A-Za-z][A-Za-z0-9_]*)\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val attrRegex =
            Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val attrSingleQuoteRegex =
            Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE)

    /** 这段正文是不是以红包/转账标记开头。 */
    fun looksLikePayment(content: String): Boolean {
        if (content.isBlank()) return false
        if (!content.contains('<')) return false
        val head = content.trimStart()
        val match = tagRegex.find(head) ?: return false
        if (match.range.first != 0) return false
        return isPaymentTag(match.groupValues[1])
    }

    /**
     * 解析整条消息。不是红包/转账就返回 null。
     *
     * @param messageSender 消息本身的发送方（"user" / "ai"），标记里没写 from 时用它兜底。
     */
    fun parse(content: String, messageSender: String = ""): ChatPayment? {
        if (content.isBlank() || !content.contains('<')) return null

        val match = tagRegex.find(content) ?: return null
        val tag = match.groupValues[1]
        if (!isPaymentTag(tag)) return null

        val attrs = readAttrs(match.groupValues[2])
        val amount = normalizeAmount(attrs["amount"]) ?: return null
        val from =
                attrs["from"]?.takeIf { it.isNotBlank() }
                        ?: messageSender.takeIf { it.isNotBlank() }
                                ?: ChatPayment.FROM_USER
        val senderName = attrs["sender"].orEmpty().trim()
        val trailing = content.replaceRange(match.range, "").trim()

        return if (tag.equals(TAG_RED_PACKET, ignoreCase = true)) {
            ChatPayment.RedPacket(
                    amount = amount,
                    greeting = attrs["greeting"]?.takeIf { it.isNotBlank() } ?: DEFAULT_GREETING,
                    from = from,
                    senderName = senderName,
                    trailingText = trailing,
            )
        } else {
            ChatPayment.Transfer(
                    amount = amount,
                    note = attrs["note"].orEmpty(),
                    from = from,
                    senderName = senderName,
                    trailingText = trailing,
            )
        }
    }

    fun buildRedPacket(
            amount: String,
            greeting: String,
            extraText: String = "",
            from: String = ChatPayment.FROM_USER,
            senderName: String = "",
    ): String {
        val normalized = normalizeAmount(amount) ?: return ""
        val tag =
                buildString {
                    append("<")
                    append(TAG_RED_PACKET)
                    append(" amount=\"")
                    append(normalized)
                    append('"')
                    append(" greeting=\"")
                    append(escape(greeting.ifBlank { DEFAULT_GREETING }))
                    append('"')
                    if (senderName.isNotBlank()) {
                        append(" sender=\"")
                        append(escape(senderName))
                        append('"')
                    }
                    append(" from=\"")
                    append(escape(from))
                    append('"')
                    append("/>")
                }
        return appendExtra(tag, extraText)
    }

    fun buildTransfer(
            amount: String,
            note: String,
            extraText: String = "",
            from: String = ChatPayment.FROM_USER,
            senderName: String = "",
    ): String {
        val normalized = normalizeAmount(amount) ?: return ""
        val tag =
                buildString {
                    append("<")
                    append(TAG_TRANSFER)
                    append(" amount=\"")
                    append(normalized)
                    append('"')
                    if (note.isNotBlank()) {
                        append(" note=\"")
                        append(escape(note))
                        append('"')
                    }
                    if (senderName.isNotBlank()) {
                        append(" sender=\"")
                        append(escape(senderName))
                        append('"')
                    }
                    append(" from=\"")
                    append(escape(from))
                    append('"')
                    append("/>")
                }
        return appendExtra(tag, extraText)
    }

    /** 金额统一成两位小数的字符串；非法或非正数返回 null。 */
    fun normalizeAmount(raw: String?): String? {
        val cleaned =
                raw.orEmpty()
                        .trim()
                        .replace("¥", "")
                        .replace("￥", "")
                        .replace(",", "")
                        .replace("，", "")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) return null
        return String.format(Locale.US, "%.2f", value)
    }

    private fun appendExtra(tag: String, extraText: String): String {
        val extra = extraText.trim()
        return if (extra.isEmpty()) tag else tag + "\n" + extra
    }

    private fun isPaymentTag(raw: String): Boolean =
            raw.equals(TAG_RED_PACKET, ignoreCase = true) ||
                    raw.equals(TAG_TRANSFER, ignoreCase = true)

    private fun readAttrs(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        attrRegex.findAll(raw).forEach { result[it.groupValues[1].lowercase(Locale.US)] = it.groupValues[2] }
        attrSingleQuoteRegex.findAll(raw).forEach {
            result.putIfAbsent(it.groupValues[1].lowercase(Locale.US), it.groupValues[2])
        }
        return result
    }

    /** 属性值里不能出现双引号，中文输入法打出来的那对就换成全角，肉眼读起来没差别。 */
    private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\u201C").replace("\n", " ")
}
