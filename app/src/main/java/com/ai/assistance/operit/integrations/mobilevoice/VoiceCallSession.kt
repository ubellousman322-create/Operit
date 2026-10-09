package com.ai.assistance.operit.integrations.mobilevoice

/**
 * 通话进行中的临时标记。
 *
 * 耳畔把用户说的话转成文字后，走的是和打字发送完全相同的那条消息链路，
 * 两者在提示词组装那里没有区别。为了让“通话专用提示词”只在通话时出现，
 * 由耳畔直连宿主在整轮通话请求的窗口期内把提示词放在这里，
 * 系统提示组装与输入钩子各自读一次，用完即清。
 *
 * 只存在内存里，不落盘、不进聊天记录；窗口期由 begin/end 成对界定。
 */
object VoiceCallSession {

    @Volatile
    private var prompt: String? = null

    @Volatile
    private var active: Boolean = false

    fun begin(callPrompt: String?) {
        prompt = callPrompt?.trim()?.takeIf { it.isNotEmpty() }
        active = true
    }

    fun end() {
        active = false
        prompt = null
    }

    /** 通话窗口期内、且这段文字非空时，返回要贴在系统提示末尾的内容。 */
    fun promptForSystemSuffix(): String? = if (active) prompt else null

    fun isActive(): Boolean = active
}
