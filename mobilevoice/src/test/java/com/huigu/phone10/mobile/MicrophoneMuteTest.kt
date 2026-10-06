package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MicrophoneMuteTest {
    @Test fun muteDuringThinkingKeepsReplyAndRejectsNewRecording() = runBlocking {
        val thinking = CompletableDeferred<Unit>()
        val continueReply = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        var recognitions = 0
        val flow = VoiceConversation(this, { recognitions++; "用户已经说完的话" }, { _, chunk ->
            thinking.complete(Unit); continueReply.await(); chunk("继续完整回答。")
        }, spoken::add, {}, {})
        val turn = flow.submit(byteArrayOf(1))
        thinking.await()
        flow.setMicrophoneEnabled(false)
        val survivedMute = turn.isActive
        val acceptsWhileMuted = flow.acceptsSpeech()
        flow.speechStarted(); val extra = flow.submit(byteArrayOf(2))
        continueReply.complete(Unit); turn.join(); extra.join()
        assertTrue(survivedMute)
        assertFalse(acceptsWhileMuted)
        assertEquals(1, recognitions)
        assertEquals(listOf("继续完整回答。"), spoken)
    }

    @Test fun muteAndUnmuteDuringAudioKeepCurrentStreamAndTail() = runBlocking {
        val playing = CompletableDeferred<Unit>()
        val finishAudio = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        var stops = 0
        val flow = VoiceConversation(this, { "你好" }, { _, chunk -> chunk("第一句。"); chunk("最后一句。") },
            {}, { stops++ }, {}, streamSpeak = { input ->
                for (part in input) {
                    playing.complete(Unit); finishAudio.await(); spoken.add(part)
                }
            })
        val turn = flow.submit(byteArrayOf(1)); playing.await()
        val initialStops = stops
        flow.setMicrophoneEnabled(false)
        flow.speechStarted(); val extra = flow.submit(byteArrayOf(2))
        flow.setMicrophoneEnabled(true)
        val survivedToggle = turn.isActive
        finishAudio.complete(Unit); turn.join(); extra.join()
        assertTrue(survivedToggle)
        assertEquals(initialStops, stops)
        assertEquals(listOf("第一句。", "最后一句。"), spoken)
        assertTrue(flow.microphoneEnabled)
    }

    @Test fun alreadySubmittedRecordingFinishesRecognitionAfterMute() = runBlocking {
        val recognizing = CompletableDeferred<Unit>()
        val finishRecognition = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val flow = VoiceConversation(this, { recognizing.complete(Unit); finishRecognition.await(); "已经提交" },
            { text, _ -> sent.add(text) }, {}, {}, {})
        val turn = flow.submit(byteArrayOf(1)); recognizing.await()
        flow.setMicrophoneEnabled(false); finishRecognition.complete(Unit); turn.join()
        assertEquals(listOf("已经提交"), sent)
    }

    @Test fun explicitHangupStillCancelsWhileMicrophoneIsMuted() = runBlocking {
        val thinking = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val flow = VoiceConversation(this, { "你好" }, { _, _ ->
            thinking.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }, {}, {}, {})
        val turn = flow.submit(byteArrayOf(1)); thinking.await()
        flow.setMicrophoneEnabled(false)
        val aliveBeforeHangup = turn.isActive
        flow.interrupt()
        withTimeout(2000) { turn.join(); stopped.await() }
        assertTrue(aliveBeforeHangup)
        assertTrue(turn.isCancelled)
    }
}
