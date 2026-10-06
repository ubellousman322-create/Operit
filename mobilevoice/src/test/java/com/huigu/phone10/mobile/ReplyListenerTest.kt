package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReplyListenerTest {
    @Test fun textReplyUsesTtsWithoutRecognitionOrModelRequest() = runBlocking {
        val spoken = mutableListOf<String>()
        val flow = VoiceConversation(this, { error("STT must stay off") }, { _, _ -> error("no model send") },
            spoken::add, {}, {})
        flow.setMicrophoneEnabled(false)
        flow.playReply("你好。完整的最后一句。").join()
        assertEquals("你好。完整的最后一句。", spoken.joinToString(""))
        assertFalse(flow.microphoneEnabled)
    }

    @Test fun baselineIsSilentAndNewRepliesAreQueuedOnceInOrder() = runBlocking {
        val spoken = mutableListOf<String>()
        val cursors = mutableListOf<Long?>()
        val heard = CompletableDeferred<Unit>()
        val listener = ReplyListener({ after ->
            cursors += after
            if (after == null) ReplySnapshot(10, emptyList(), false)
            else ReplySnapshot(12, listOf(ObservedReply(11,"第一条"),ObservedReply(12,"第二条")), false)
        }, { chunks -> launch { delay(10); for(text in chunks) spoken += text; if(spoken.size==2) heard.complete(Unit) } }, {}, {}, 5)
        val job = launch { listener.run() }
        withTimeout(2000) { heard.await() }; delay(30); job.cancelAndJoin()
        assertEquals(listOf("第一条","第二条"),spoken)
        assertNull(cursors.first()); assertTrue(cursors.contains(12L))
    }

    @Test fun stopCancelsCurrentAndQueuedAudioButKeepsObserving() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        var latest = 12L
        val listener = ReplyListener({ after ->
            if(after==null) ReplySnapshot(10,emptyList(),false)
            else ReplySnapshot(latest, if(latest==12L) listOf(ObservedReply(11,"当前"),ObservedReply(12,"排队"))
                else listOf(ObservedReply(13,"新回复")),false)
        }, { chunks -> launch { for(text in chunks) { spoken += text; if(text=="当前"){started.complete(Unit); awaitCancellation()} } } }, {}, {}, 5)
        val job=launch {listener.run()};withTimeout(2000){started.await()}
        listener.interrupt();latest=13
        withTimeout(2000){while(!spoken.contains("新回复"))delay(5)}
        job.cancelAndJoin(); assertEquals(listOf("当前","新回复"),spoken)
    }

    @Test fun listenOnlyValidationAllowsEmptySttButCallStillRequiresIt() {
        val speech=SpeechConfig.openAiDefaults().copy(ttsKey="test-key")
        val listen=MobileSettings(speech,chatId="chat",listenOnly=true,smartEndpoint=true)
        assertTrue(configurationIssues(listen).isEmpty())
        assertTrue(configurationIssues(listen.copy(listenOnly=false)).any{it.contains("识别 API Key")})
        assertTrue(configurationIssues(listen.copy(speech=speech.copy(ttsKey=""))).any{it.contains("合成 API Key")})
        assertEquals(1,listen.saveVoiceProfile("只听").profiles().size)
    }
}
