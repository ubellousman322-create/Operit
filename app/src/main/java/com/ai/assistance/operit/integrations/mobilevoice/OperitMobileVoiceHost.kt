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
    private val executor = ExternalChatRequestExecutor(hostContext)
    private val chatTool = StandardChatManagerTool(hostContext)
    private val streamingSessions = ConcurrentHashMap<String, () -> Unit>()

    override fun dispatch(context: Context, pending: OperitPending) {
        scope.launch {
            try {
                when (pending.kind) {
                    "list" -> handleList(pending)
                    "reply" -> handleReply(pending)
                    else -> pending.accept(fail(pending, "OPERIT_UNAVAILABLE"))
                }
            } catch (t: Throwable) {
                pending.accept(fail(pending, "OPERIT_UNAVAILABLE"))
            } finally {
                streamingSessions.remove(pending.id)
            }
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
        val result = chatTool.listChats(AITool(name = "list_chats"))
        val data = result.result as? ChatListResultData ?: run {
            pending.accept(fail(pending, "OPERIT_LIST_FAILED"))
            return
        }
        val chats = data.chats
        if (chats.isEmpty()) {
            val empty = event(pending, "chats")
            empty.add("chats", JsonArray())
            pending.accept(empty)
            pending.accept(event(pending, "complete"))
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
            pending.accept(payload)
        }
        pending.accept(event(pending, "complete"))
    }

    private suspend fun handleReply(pending: OperitPending) {
        val text = pending.text
        if (text.isNullOrBlank()) {
            pending.accept(fail(pending, "OPERIT_INVALID_REQUEST"))
            return
        }
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
                pending.accept(fail(pending, "OPERIT_REPLY_FAILED"))
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
                        pending.accept(payload)
                    }
                }
                // 流收完了就是这一轮结束；中途出错会在 collect 里抛，落到上面的 catch。
                pending.accept(event(pending, "complete"))
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
