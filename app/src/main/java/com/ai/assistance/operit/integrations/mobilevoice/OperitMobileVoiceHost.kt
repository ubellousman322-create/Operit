package com.ai.assistance.operit.integrations.mobilevoice

import android.content.Context
import com.ai.assistance.operit.core.tools.ChatListResultData
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardChatManagerTool
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.integrations.externalchat.ExternalChatRequest
import com.ai.assistance.operit.integrations.externalchat.ExternalChatRequestExecutor
import com.ai.assistance.operit.integrations.externalchat.ExternalChatResponseSanitizer
import com.ai.assistance.operit.integrations.externalchat.ExternalChatStreamingStartResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.huigu.phone10.mobile.OperitLocalVoiceHost
import com.huigu.phone10.mobile.OperitPending
import com.huigu.phone10.mobile.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 耳畔内置直连实现。
 *
 * 耳畔通过广播把请求交给插件沙箱，再由插件调 Operit 的 Chat API，绕三跳。
 * 内置之后由这里直接应答：不再有广播、不再有插件、不再有 JS，
 * 也不再需要另一个 app 装着。
 *
 * 行为与走插件时保持一致：先应答应答事件（version/id/nonce 对齐），
 * 流式增量以 chunk 事件推送，结束时发 complete，出错发 error。
 */
class OperitMobileVoiceHost(private val hostContext: Context) : OperitLocalVoiceHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 构造时只存引用，真正要用的时候才建 —— 这样这个对象在进程最早的时刻
    // 也能安全地 new 出来，不会因为依赖还没就绪就被吞掉。
    private val executor by lazy { ExternalChatRequestExecutor(hostContext) }
    private val chatTool by lazy { StandardChatManagerTool(hostContext) }
    private val streamingSessions = ConcurrentHashMap<String, () -> Unit>()

    override fun dispatch(context: Context, pending: OperitPending) {
        scope.launch {
            try {
                when (pending.kind) {
                    "list" -> handleList(pending)
                    "reply" -> handleReply(pending)
                    else -> pending.acceptEmbedded(fail(pending, "OPERIT_UNAVAILABLE"))
                }
            } catch (t: Throwable) {
                writeDiagnostic(pending, t)
                pending.acceptEmbedded(fail(pending, "OPERIT_UNAVAILABLE"))
            } finally {
                streamingSessions.remove(pending.id)
            }
        }
    }

    /**
     * 把真实错误写成一个我可以直接读的文件。
     * 直连一旦断在内部，耳机那边只看到“没拿到”，看不到为什么。
     */
    private fun writeDiagnostic(pending: OperitPending, t: Throwable) {
        runCatching {
            val file = java.io.File(hostContext.getExternalFilesDir(null), "erpan-host-error.log")
            val stamp =
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
            val sep = System.lineSeparator()
            file.appendText(
                "[" + stamp + "] kind=" + pending.kind + " id=" + pending.id + sep +
                    android.util.Log.getStackTraceString(t) + sep + sep
            )
        }
    }

    override fun callAvatar(): android.graphics.drawable.Drawable? = runCatching {
        val dir = java.io.File(hostContext.getExternalFilesDir(null), "Pictures")
        val newest =
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("cropped") }
                ?.maxByOrNull { it.lastModified() }
                ?: return@runCatching null
        android.graphics.drawable.Drawable.createFromPath(newest.absolutePath)
    }.getOrNull()

    override fun cancel(context: Context, target: OperitPending) {
        target.cancel()
        streamingSessions.remove(target.id)?.invoke()
    }

    private suspend fun handleList(pending: OperitPending) {
        // 这个列表是从一个带初始空值的共享 Flow（stateIn Lazily + emptyList）里读出来的：
        // 第一次读常常就是那个空壳，Room 的查询还没回来。空就等一会儿再要一次，
        // 别把“还没读出来”当成“没有聊天”。
        var chats = listChatsOrNull(pending) ?: return
        if (chats.isEmpty()) {
            kotlinx.coroutines.delay(1500L)
            chats = listChatsOrNull(pending) ?: return
        }
        if (chats.isEmpty()) {
            val empty = event(pending, "chats")
            empty.add("chats", JsonArray())
            pending.acceptEmbedded(empty)
            pending.acceptEmbedded(event(pending, "complete"))
            return
        }
        // 耳畔侧一批最多接受 40 条，这里照办。
        chats.chunked(40).forEach { batch ->
            val payload = event(pending, "chats")
            val array = JsonArray()
            batch.forEach { chat ->
                array.add(
                    JsonObject().apply {
                        addProperty("id", chat.id)
                        addProperty("title", chat.title.ifBlank { chat.id })
                    }
                )
            }
            payload.add("chats", array)
            pending.acceptEmbedded(payload)
        }
        pending.acceptEmbedded(event(pending, "complete"))
    }

    private suspend fun listChatsOrNull(pending: OperitPending): List<ChatListResultData.ChatInfo>? {
        val result = chatTool.listChats(AITool(name = "list_chats"))
        val data = result.result as? ChatListResultData
        if (data == null) {
            writeDiagnostic(
                pending,
                IllegalStateException(
                    "listChats returned " + (result.result?.javaClass?.name ?: "null") + ", error=" + result.error
                )
            )
            pending.acceptEmbedded(fail(pending, "OPERIT_LIST_FAILED"))
            return null
        }
        return data.chats
    }

    private suspend fun handleReply(pending: OperitPending) {
        val text = pending.text
        if (text.isNullOrBlank()) {
            pending.acceptEmbedded(fail(pending, "OPERIT_INVALID_REQUEST"))
            return
        }
        // 通话进行中：这一轮的系统提示末尾要贴通话专用提示词。
        // 打字聊天不走这里，所以那边一个字都不会变；异常也会在 finally 里清干净。
        VoiceCallSession.begin(readCallPrompt())
        try {
            handleReplyTurn(pending, text)
        } finally {
            VoiceCallSession.end()
        }
    }

    /** 通话专用提示词写在耳畔的连接配置里；同一进程同一个包，直接读加密设置即可。 */
    private fun readCallPrompt(): String? =
        runCatching { SettingsStore(hostContext).load().callPrompt }.getOrNull()

    private suspend fun handleReplyTurn(pending: OperitPending, text: String) {
        val request = ExternalChatRequest(
            requestId = pending.id,
            message = text,
            chatId = pending.chatId?.takeIf { it.isNotBlank() },
            createNewChat = false,
            createIfNone = true,
            showFloating = false,
            returnToolStatus = true
        )
        when (val started = executor.startStreaming(request)) {
            is ExternalChatStreamingStartResult.Failed -> {
                pending.acceptEmbedded(fail(pending, "OPERIT_REPLY_FAILED"))
            }
            is ExternalChatStreamingStartResult.Started -> {
                val session = started.session
                streamingSessions[pending.id] = { session.responseStreamSession.cancel() }
                val sanitized = ExternalChatResponseSanitizer.sanitizeStream(
                    session.responseStreamSession.responseStream,
                    true
                )
                sanitized.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        val payload = event(pending, "chunk")
                        payload.addProperty("text", chunk)
                        pending.acceptEmbedded(payload)
                    }
                }
                // 流收完了就是这一轮结束；中途出错会在 collect 里抛，落到上面的 catch。
                pending.acceptEmbedded(event(pending, "complete"))
                session.cleanup()
            }
        }
    }

    private fun event(pending: OperitPending, type: String): JsonObject =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("id", pending.id)
            addProperty("nonce", pending.nonce)
            addProperty("type", type)
        }

    private fun fail(pending: OperitPending, code: String): JsonObject =
        event(pending, "error").apply { addProperty("code", code) }
}
