package com.ai.assistance.operit.data.model

/**
 * 拍一拍。
 *
 * 双击头像不是"发一条普通消息"，是伸手碰一下对方，所以它走一条独立的消息标记：
 *
 *   <pat suffix="的脑袋"/>
 *
 * 用户发出来的整条消息只有这个标记时，聊天页不会画气泡，只在中间留一行小字。
 * 模型那条回复里也可以夹一个同样的标记，渲染时会被抠出来单独放，正文照旧显示。
 */
sealed class ChatPatEvent {
    abstract val suffix: String

    /** 标记抠掉之后剩下的正文，可能为空。 */
    abstract val remainingText: String

    /** 用户拍的（双击了 AI 头像）。 */
    data class FromUser(
            override val suffix: String,
            override val remainingText: String = "",
    ) : ChatPatEvent()

    /** AI 拍的。 */
    data class FromAi(
            override val suffix: String,
            override val remainingText: String = "",
    ) : ChatPatEvent()
}

object ChatPatMarkup {

    private const val TAG = "pat"

    private val patRegex = Regex("<pat\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val attrRegex =
            Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val attrSingleQuoteRegex =
            Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE)

    fun contains(content: String): Boolean =
            content.contains('<') && patRegex.containsMatchIn(content)

    /**
     * 解析一条消息里的拍一拍。不是拍一拍就返回 null。
     *
     * @param sender 消息发送方，"user" 或 "ai"。
     */
    fun parse(content: String, sender: String): ChatPatEvent? {
        if (!contains(content)) return null
        val match = patRegex.find(content) ?: return null
        val attrs = readAttrs(match.groupValues[1])
        val suffix = attrs["suffix"].orEmpty().trim()
        val remaining = content.replaceRange(match.range, "").trim()
        return if (sender == ChatPayment.FROM_AI) {
            ChatPatEvent.FromAi(suffix = suffix, remainingText = remaining)
        } else {
            ChatPatEvent.FromUser(suffix = suffix, remainingText = remaining)
        }
    }

    /** 生成用户这边的拍一拍消息内容。 */
    fun buildFromUser(suffix: String): String =
            if (suffix.isBlank()) "<$TAG/>"
            else "<$TAG suffix=\"${escape(suffix)}\"/>"

    /** 生成 AI 这边的拍一拍标记，会嵌在回复正文里。 */
    fun buildFromAi(suffix: String): String =
            if (suffix.isBlank()) "<$TAG/>"
            else "<$TAG suffix=\"${escape(suffix)}\"/>"

    /** 一行小字：谁拍了拍谁，带不带尾巴。 */
    fun displayText(event: ChatPatEvent, roleName: String): String {
        val who = roleName.ifBlank { "对方" }
        val tail = event.suffix.trim()
        return when (event) {
            is ChatPatEvent.FromUser -> "我拍了拍 $who" + if (tail.isEmpty()) "" else " $tail"
            is ChatPatEvent.FromAi -> "$who 拍了拍你" + if (tail.isEmpty()) "" else " $tail"
        }
    }

    private fun readAttrs(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        attrRegex.findAll(raw).forEach {
            result[it.groupValues[1].lowercase()] = it.groupValues[2]
        }
        attrSingleQuoteRegex.findAll(raw).forEach {
            result.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2])
        }
        return result
    }

    private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\u201C").replace("\n", " ")
}