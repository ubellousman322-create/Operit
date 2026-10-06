package com.huigu.phone10.mobile

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VoiceDraftReviewTest {
    @Test fun confirmationHoldsOriginalThenSendsOnlyEditedTextOnce() = runBlocking {
        val sent = mutableListOf<String>()
        val events = mutableListOf<PendingVoiceDraft?>()
        val flow = VoiceConversation(this, { "识别错字" }, { text, _ -> sent += text }, {}, {}, {},
            confirmBeforeSend = true, onDraftChanged = { events += it })

        flow.submit(byteArrayOf(1)).join()
        assertTrue(sent.isEmpty())
        val draft = requireNotNull(flow.pendingDraft())
        assertEquals("识别错字", draft.text)
        assertFalse(flow.acceptsSpeech())
        assertTrue(flow.editDraft(draft.id, "修改后的正文"))
        val sending = requireNotNull(flow.confirmDraft(draft.id))
        assertNull(flow.confirmDraft(draft.id))
        sending.join()

        assertEquals(listOf("修改后的正文"), sent)
        assertNull(flow.pendingDraft())
        assertEquals("修改后的正文", events.last { it != null }!!.text)
        assertNull(events.last())
    }

    @Test fun blankDraftCannotSendAndCancelNeverCallsChat() = runBlocking {
        var sends = 0
        val flow = VoiceConversation(this, { "原文" }, { _, _ -> sends++ }, {}, {}, {},
            confirmBeforeSend = true)
        flow.submit(byteArrayOf(1)).join()
        val id = requireNotNull(flow.pendingDraft()).id
        assertTrue(flow.editDraft(id, " \n "))
        assertNull(flow.confirmDraft(id))
        assertEquals(0, sends)
        assertTrue(flow.cancelDraft(id))
        assertNull(flow.confirmDraft(id))
        assertTrue(flow.acceptsSpeech())
        assertEquals(0, sends)
    }

    @Test fun pendingDraftRejectsAnotherRecordingAndSurvivesManualMute() = runBlocking {
        var recognitions = 0
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { recognitions++; "原文" }, { text, _ -> sent += text }, {}, {}, {},
            confirmBeforeSend = true)
        flow.submit(byteArrayOf(1)).join()
        val draft = requireNotNull(flow.pendingDraft())
        flow.speechStarted()
        flow.submit(byteArrayOf(2)).join()
        flow.setMicrophoneEnabled(false)
        assertEquals(draft, flow.pendingDraft())
        assertEquals(1, recognitions)
        assertFalse(flow.acceptsSpeech())
        requireNotNull(flow.confirmDraft(draft.id)).join()
        assertEquals(listOf("原文"), sent)
        assertFalse(flow.microphoneEnabled)
    }

    @Test fun manualConfirmationSkipsSmartJudgeIncludingUnavailableResult() = runBlocking {
        var judgeCalls = 0
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { "我说完了" }, { text, _ -> sent += text }, {}, {}, {},
            judgeEnd = { judgeCalls++; null }, confirmBeforeSend = true)
        flow.submit(byteArrayOf(1)).join()
        assertEquals(0, judgeCalls)
        assertTrue(sent.isEmpty())
        requireNotNull(flow.confirmDraft(requireNotNull(flow.pendingDraft()).id)).join()
        assertEquals(listOf("我说完了"), sent)
        assertEquals(0, judgeCalls)
    }

    @Test fun interruptionDiscardsDraftAndLateRecognitionCannotRestoreIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        val flow = VoiceConversation(this, {
            started.complete(Unit)
            withContext(NonCancellable) { release.await(); "迟到原文" }
        }, { _, _ -> sends++ }, {}, {}, {}, confirmBeforeSend = true)
        val old = flow.submit(byteArrayOf(1))
        started.await()
        flow.interrupt()
        release.complete(Unit)
        old.join()
        assertNull(flow.pendingDraft())
        assertEquals(0, sends)
    }

    @Test fun staleDraftIdFromPriorSessionCannotSendCurrentDraft() = runBlocking {
        val first = VoiceConversation(this, { "旧话" }, { _, _ -> fail("old call sent") }, {}, {}, {}, confirmBeforeSend = true)
        first.submit(byteArrayOf(1)).join()
        val oldId = requireNotNull(first.pendingDraft()).id
        first.interrupt()
        val sent = mutableListOf<String>()
        val next = VoiceConversation(this, { "新话" }, { text, _ -> sent += text }, {}, {}, {}, confirmBeforeSend = true)
        next.submit(byteArrayOf(2)).join()
        assertNull(next.confirmDraft(oldId))
        assertEquals("新话", next.pendingDraft()?.text)
        assertTrue(sent.isEmpty())
    }

    @Test fun recognitionFailureCreatesNoDraftOrChatRequest() = runBlocking {
        var sends = 0
        val reports = mutableListOf<String>()
        val flow = VoiceConversation(this, { throw IOException("private provider response") },
            { _, _ -> sends++ }, {}, {}, reports::add, confirmBeforeSend = true)
        flow.submit(byteArrayOf(1)).join()
        assertNull(flow.pendingDraft())
        assertEquals(0, sends)
        assertTrue(reports.last().contains("失败"))
        assertFalse(reports.last().contains("private provider response"))
    }

    @Test fun editedTranscriptNeverReintroducesOldWordsOrStaleVoiceHints() {
        val sentence = JsonParser.parseString("""{"begin_time":0,"end_time":1000,"emo_tag":"positive","emo_confidence":0.9}""").asJsonObject
        val transcript = SpeechTranscript.fromBailian("识别原文", listOf(sentence))
        val changed = transcript.withText("真正要发的字")
        assertEquals("真正要发的字", changed.forChat(true))
        assertFalse(changed.forChat(true).contains("识别原文"))
        assertTrue(transcript.withText("识别原文").forChat(true).contains("声音线索"))
    }

    @Test fun oldSettingsDefaultToAutomaticSend() = runBlocking {
        val gson = Gson()
        val legacy = gson.fromJson("""{"speech":${gson.toJson(SpeechConfig.bailianDefaults())}}""", MobileSettings::class.java)
        assertFalse(legacy.confirmBeforeSend)
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { "原文" }, { text, _ -> sent += text }, {}, {}, {},
            confirmBeforeSend = legacy.confirmBeforeSend)
        flow.submit(byteArrayOf(1)).join()
        assertEquals(listOf("原文"), sent)
    }
}
