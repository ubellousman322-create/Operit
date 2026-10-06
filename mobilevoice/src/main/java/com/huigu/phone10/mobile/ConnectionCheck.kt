package com.huigu.phone10.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit settings-page probe. Uses existing adapters and never sends a chat turn. */
internal class ConnectionCheck(
    private val listChats: suspend () -> List<OperitChat>,
    private val checkRecognition: suspend () -> Unit,
    private val checkSynthesis: suspend () -> Unit,
    private val checkJudge: suspend () -> Boolean?,
    private val timeoutMillis: Long = 25_000,
) {
    suspend fun run(settings: MobileSettings, report: (String) -> Unit): String {
        val problems = configurationIssues(settings)
        if (problems.isNotEmpty()) return problems.joinToString("\n\n").also(report)
        val results = mutableListOf<String>()
        suspend fun check(label: String, failure: String, action: suspend () -> String) {
            currentCoroutineContext().ensureActive()
            report((results + "$label：正在检查…").joinToString("\n\n"))
            val result = try {
                withTimeoutOrNull(timeoutMillis) { action() } ?: "检查超时，请检查网络和服务地址。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                describeConnectionFailure(error, failure)
            }
            results += "$label：$result"
            report(results.joinToString("\n\n"))
        }
        check("聊天连接", "未读到聊天列表，请打开 Operit，检查连接插件和工作流是否启用。") {
            if (listChats().any { it.id == settings.chatId }) "已找到所选聊天；未发送测试消息。"
            else "已连接 Operit，但未找到所选聊天，请重新选择聊天窗口。"
        }
        if (!settings.listenOnly) check("语音识别", "检查失败，请核对识别地址、密钥、模型和网络。") {
            checkRecognition()
            "接口已响应静音测试；人声识别请在通话中确认。"
        }
        check("语音合成", "检查失败，请核对合成地址、密钥、模型、音色 ID 和网络。") {
            checkSynthesis()
            "已收到测试音频；本次未播放。"
        }
        if (!settings.listenOnly && settings.smartEndpoint && !settings.confirmBeforeSend) check("智能结束判断", "检查失败，请核对判断服务地址、密钥和模型。") {
            if (checkJudge() != null) "判断接口已响应。"
            else "未得到有效判断，请核对地址、密钥、模型和网络。"
        }
        return results.joinToString("\n\n")
    }
}

internal fun configurationIssues(settings: MobileSettings): List<String> = buildList {
    if (settings.chatId.isBlank()) add("聊天连接：请先选中聊天窗口。")
    addAll(settings.speech.validationErrors(includeRecognition = !settings.listenOnly))
    if (!settings.listenOnly && settings.smartEndpoint && !settings.confirmBeforeSend) {
        val judge = settings.endJudge
        if (judge == null) add("智能结束判断：请填写判断服务。")
        else try { judge.validate() } catch (invalid: IllegalArgumentException) {
            add("智能结束判断：${invalid.message}")
        }
    }
}

private fun describeConnectionFailure(error: Exception, fallback: String): String {
    // Provider adapters expose fixed safe messages; transport exceptions may contain credentials.
    if (error !is SpeechApiException) return fallback
    val message = error.message.orEmpty()
    return when {
        "HTTP 401" in message || "HTTP 403" in message -> "密钥或调用权限未通过，请核对密钥、账号地域和模型开通状态。"
        "HTTP 404" in message -> "未找到接口，请核对接口地址和模型名称。"
        "HTTP 429" in message -> "服务暂时限制调用，请检查额度或稍后重试。"
        else -> message.ifBlank { fallback }
    }
}
