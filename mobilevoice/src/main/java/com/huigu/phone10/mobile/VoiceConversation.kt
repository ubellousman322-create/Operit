package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class PendingVoiceDraft(val id: String, val text: String)

class VoiceConversation(
    private val scope: CoroutineScope,
    private val transcribe: suspend (ByteArray)->String,
    private val reply: suspend (String, suspend (String)->Unit)->Unit,
    private val speak: suspend (String)->Unit,
    private val stopPlayback: ()->Unit,
    private val report: (String)->Unit,
    private val streamSpeak: (suspend (ReceiveChannel<String>)->Unit)? = null,
    private val judgeEnd: (suspend (String)->Boolean?)? = null,
    private val continuationMillis: Long = 1500,
    private val transcribeDetailed: (suspend (ByteArray)->SpeechTranscript)? = null,
    private val sentenceTts: Boolean = false,
    private val onReplyStart: () -> Unit = {},
    private val onReplyDelta: (String) -> Unit = {},
    private val diagnose: (String) -> Unit = VoiceDiagnostics::record,
    private val wholeReplyTts: Boolean = false,
    private val confirmBeforeSend: Boolean = false,
    private val onDraftChanged: (PendingVoiceDraft?) -> Unit = {},
    private val firstClauseTts: Boolean = false,
    private val onSpeechSegment: (String) -> Unit = {},
    private val coherentTts: Boolean = false,
) {
    @Volatile private var active: Job? = null
    @Volatile private var voiceInterruptionEnabled = true
    private var pendingPcm = byteArrayOf()
    private var ignoreUtterance = false
    private data class Review(val id: String, val transcript: SpeechTranscript, var edited: String) {
        fun visible() = PendingVoiceDraft(id, edited)
    }
    private var review: Review? = null

    @Volatile var microphoneEnabled: Boolean = true
        private set
    /** The call owns this memory-only draft; the activity is just an editor. */
    fun pendingDraft(): PendingVoiceDraft? = review?.visible()
    fun editDraft(id: String, text: String): Boolean {
        val current = review?.takeIf { it.id == id } ?: return false
        if (text.length > 4000) return false
        current.edited = text
        onDraftChanged(current.visible())
        return true
    }
    fun confirmDraft(id: String): Job? {
        val current = review?.takeIf { it.id == id } ?: return null
        val edited = current.edited.trim()
        if (edited.isEmpty()) return null
        val message = current.transcript.withText(edited).forChat()
        review = null
        onDraftChanged(null)
        val previous = active
        return scope.launch {
            previous?.join()
            try { sendToChat(message) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: TurnFailure) {
                currentCoroutineContext().ensureActive()
                VoiceDiagnostics.record("turn_failed: ${failure.message.orEmpty()}")
                stopPlayback(); report(failure.message.orEmpty())
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                stopPlayback(); report("本轮语音失败，继续聆听；本轮不会自动重发。")
            }
        }.also { active = it }
    }
    fun cancelDraft(id: String): Boolean {
        if (review?.id != id) return false
        review = null
        onDraftChanged(null)
        report(listeningStatus())
        return true
    }
    fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
        // Drop unsubmitted capture only; an accepted utterance owns its existing
        // recognition/reply/playback job even while the microphone is muted.
        pendingPcm = byteArrayOf()
        ignoreUtterance = false
    }

    fun setVoiceInterruptionEnabled(enabled: Boolean) { voiceInterruptionEnabled = enabled }
    fun acceptsSpeech(): Boolean = microphoneEnabled && review == null &&
        (voiceInterruptionEnabled || active?.isActive != true)

    /** Call on the service's main dispatcher. No transcript or audio survives the turn. */
    fun submit(pcm: ByteArray): Job {
        if (ignoreUtterance || !acceptsSpeech()) {
            ignoreUtterance = false
            return Job().apply { complete() }
        }
        val previous = active
        previous?.cancel()
        stopPlayback()
        val judge = judgeEnd?.takeUnless { confirmBeforeSend }
        val recording = if (judge != null) (pendingPcm + pcm).also { pendingPcm = it } else pcm
        return scope.launch {
            // Operit cancellation cleanup must finish before a new send can claim the chat.
            previous?.join()
            try {
                if (judge != null && recording.size > 960_000) {
                    pendingPcm = byteArrayOf()
                    report("连续表达超过 30 秒，本轮未提交，请分段重说。")
                    return@launch
                }
                report("正在识别…")
                val transcript = stage("语音识别失败，请检查识别服务配置与网络。") {
                    transcribeDetailed?.invoke(recording) ?: SpeechTranscript(transcribe(recording))
                }
                val text = transcript.text.trim()
                if (text.isEmpty()) { pendingPcm = byteArrayOf(); report("没有识别到文字，继续聆听。" ); return@launch }
                var unavailable = false
                if (judge != null) {
                    report("正在判断是否说完…")
                    when (judge.invoke(text)) {
                        false -> { report("似乎还没说完，等你接着说…"); delay(continuationMillis) }
                        null -> { unavailable = true }
                        true -> Unit
                    }
                }
                currentCoroutineContext().ensureActive()
                pendingPcm = byteArrayOf()
                if (confirmBeforeSend) {
                    review = Review(UUID.randomUUID().toString(), transcript, transcript.text)
                    report("识别完成 · 待确认，尚未发送")
                    onDraftChanged(review?.visible())
                    return@launch
                }
                sendToChat(transcript.forChat(), unavailable)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: TurnFailure) {
                currentCoroutineContext().ensureActive()
                VoiceDiagnostics.record("turn_failed: ${failure.message.orEmpty()}")
                pendingPcm = byteArrayOf()
                stopPlayback(); report(failure.message.orEmpty())
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                pendingPcm = byteArrayOf()
                stopPlayback(); report("本轮语音失败，继续聆听；本轮不会自动重发。")
            }
        }.also { active = it }
    }

    fun interrupt() {
        ignoreUtterance = false
        pendingPcm = byteArrayOf()
        if (review != null) { review = null; onDraftChanged(null) }
        active?.cancel()
        stopPlayback()
        report("正在聆听…")
    }

    fun speechStarted() {
        ignoreUtterance = !acceptsSpeech()
        if (ignoreUtterance) return
        // A resumed utterance retains buffered audio until it has actually been sent to Operit.
        // Explicit interruption discards the pending input and cancels the turn.
        active?.cancel()
        stopPlayback()
        report("正在聆听…")
    }

    /** Speak frontend text without recognition or sending another model request. */
    fun playReply(text: String): Job = playFrontend { chunk -> chunk(text) }

    fun playReplyStream(chunks: ReceiveChannel<String>): Job = playFrontend { chunk ->
        for (text in chunks) chunk(text)
    }

    private fun playFrontend(source: suspend (suspend (String) -> Unit) -> Unit): Job {
        val previous = active
        previous?.cancel()
        stopPlayback()
        return scope.launch {
            previous?.join()
            try {
                val speechFailure = streamReply("") { _, chunk -> source(chunk) }
                report(if (speechFailure == null) "只听回复已开启 · 等待新的回复"
                    else "本条朗读未完成 · 等待新的回复")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: TurnFailure) { stopPlayback(); report(failure.message.orEmpty()) }
            catch (_: Exception) { stopPlayback(); report("本条回复播放失败；等待下一条，不会自动重播。") }
        }.also { active = it }
    }

    private fun listeningStatus(): String = when {
        confirmBeforeSend -> "正在聆听 · 约 0.55 秒后待确认"
        judgeEnd == null -> "正在聆听 · 约 0.55 秒静音提交"
        else -> "正在聆听 · 智能结束判断"
    }

    private suspend fun sendToChat(text: String, unavailable: Boolean = false) {
        report(if (unavailable) "智能判断不可用，已按静音提交；等待 Operit 回复…" else "等待 Operit 回复…")
        val speechFailure = streamReply(text)
        report(if (speechFailure == null) listeningStatus()
            else "回复已完成，本条朗读未完成 · ${listeningStatus()}")
    }

    private suspend fun streamReply(text: String,
        source: suspend (String, suspend (String) -> Unit) -> Unit = reply) = coroutineScope {
        val replyContext = currentCoroutineContext()
        deliverCaption { onReplyStart() }
        if (wholeReplyTts) report("整段模式 · 等待回复写完…")
        // Model text must drain independently of speech playback. Bound memory by
        // total readable characters, not by 32 sentences that can fill in a second.
        val queue = Channel<String>(Channel.UNLIMITED)
        var readableCharacters = 0
        var firstText = true
        var speechFailure: String? = null
        val mutex = Mutex()
        // The speaker is optional. A failed speaker drops only its queued audio;
        // the same accepted O request and captions keep draining until completion.
        fun stopSpeech(message: String) {
            if (speechFailure != null) return
            speechFailure = message
            queue.cancel()
            stopPlayback()
            diagnose("tts_failed_source_continues")
            report("朗读已停止：$message 当前任务继续，文字仍会更新。")
        }
        // Qwen's whole mode is owned by its existing text-stream adapter. HTTP
        // synthesis instead needs one completed body before starting its request.
        val collectWhole = wholeReplyTts && sentenceTts
        val wholeText = StringBuilder()
        suspend fun submitSegment(segment: String) {
            if (firstText) {
                firstText = false
                diagnose("tts_first_text_chars_${segment.length}")
            }
            deliverCaption { onSpeechSegment(segment) }
            queue.send(segment)
        }
        suspend fun enqueue(segments: List<String>) {
            if (speechFailure != null) return
            for (segment in segments) {
                readableCharacters += segment.length
                if (readableCharacters > 60_000) {
                    stopSpeech("回复超过朗读长度上限。")
                    return
                }
                if (collectWhole) wholeText.append(segment) else submitSegment(segment)
            }
        }
        val buffer = SpeechText(preserveSpacing = streamSpeak != null, sentenceMode = sentenceTts,
            firstClauseMode = firstClauseTts, coherentMode = coherentTts)
        val speaker = launch {
            try {
                if (streamSpeak != null) {
                    stage("语音合成或播放失败，请检查合成服务配置与网络。") { streamSpeak.invoke(queue) }
                } else for (segment in queue) {
                    report("正在播放 Operit 的回复…")
                    stage("语音合成或播放失败，请检查合成服务配置与网络。") { speak(segment) }
                }
            } catch (cancelled: CancellationException) {
                // A provider's own deadline/closed player can throw CancellationException
                // while this task is still active. A real Stop cancels this context.
                currentCoroutineContext().ensureActive()
                mutex.withLock { stopSpeech("语音合成或播放未完成。") }
            } catch (failure: TurnFailure) {
                currentCoroutineContext().ensureActive()
                mutex.withLock { stopSpeech(failure.message.orEmpty()) }
            }
        }
        val timer = launch {
            while (isActive) {
                delay(30)
                mutex.withLock { enqueue(buffer.flushReady()) }
            }
        }
        try {
            stage("Operit 回复失败，请检查 Operit 连接与聊天状态；本轮不会自动重发。") {
                source(text) { delta -> mutex.withLock {
                    replyContext.ensureActive()
                    deliverCaption { onReplyDelta(delta) }
                    if (speechFailure == null) enqueue(buffer.push(delta))
                } }
            }
            timer.cancelAndJoin()
            mutex.withLock {
                enqueue(buffer.flush())
                if (collectWhole && speechFailure == null && wholeText.isNotEmpty()) {
                    submitSegment(wholeText.toString())
                    wholeText.clear()
                }
            }
            if (wholeReplyTts && !firstText && speechFailure == null) report("回复已写完 · 正在生成整段语音…")
            queue.close()
            diagnose("tts_source_complete")
            speaker.join()
            if (speechFailure == null) diagnose("tts_job_complete")
            speechFailure
        } finally { timer.cancel(); queue.cancel() }
    }

    private fun deliverCaption(action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { VoiceDiagnostics.record("caption_update_failed") }
    }

    private suspend fun <T> stage(message: String, action: suspend ()->T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: TurnFailure) { throw failure }
    catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        // SpeechApiException messages are fixed locally, never raw provider bodies.
        val detail = if (error is SpeechApiException) error.message.orEmpty() else message
        VoiceDiagnostics.record("stage_failed: $detail")
        throw TurnFailure(detail)
    }

    private class TurnFailure(message: String) : Exception(message)
}
