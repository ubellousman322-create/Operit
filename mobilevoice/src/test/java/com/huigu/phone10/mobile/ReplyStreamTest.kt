package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ReplyStreamTest {
    @Test fun speaksBeforeCompletionAndFinalTailIsDeliveredOnce() = runBlocking {
        val snapshots = Channel<ReplySnapshot>(8)
        val spoken = mutableListOf<String>()
        var plays = 0
        val listener = ReplyListener({ after ->
            if (after == null) ReplySnapshot(10, emptyList(), false) else snapshots.receive()
        }, { chunks -> launch { plays++; for (chunk in chunks) spoken += chunk } }, {}, {}, 1)
        val job = launch { listener.run() }
        snapshots.send(ReplySnapshot(10, listOf(ObservedReply(11,"你好。",false)),true))
        withTimeout(2000) { while(spoken.isEmpty()) delay(1) }
        assertEquals("你好。",spoken.joinToString("")) // O has not finished.
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"你好。正在说话。",false)),true))
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"你好。正在说话。最后一句。")),false))
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"你好。正在说话。最后一句。")),false))
        withTimeout(2000) { while(!spoken.joinToString("").endsWith("最后一句。")) delay(1) }
        delay(20);job.cancelAndJoin()
        assertEquals(1,plays)
        assertEquals("你好。正在说话。最后一句。",spoken.joinToString(""))
    }

    @Test fun stopSuppressesFutureDeltasButAllowsNextTurn() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(8);val spoken=mutableListOf<String>()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {chunks->launch{for(chunk in chunks)spoken+=chunk}}, {}, {},1)
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"已经听到。",false)),true))
        withTimeout(2000){while(spoken.isEmpty())delay(1)}
        listener.interrupt()
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"已经听到。不要续播。",false)),true))
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"已经听到。不要续播。")),false))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,"新一轮。")),false))
        withTimeout(2000){while(!spoken.contains("新一轮。"))delay(1)}
        job.cancelAndJoin();assertEquals("已经听到。新一轮。",spoken.joinToString(""))
    }

    @Test fun rewrittenAndFailedPartialNeverRestart() = runBlocking {
        for(failed in listOf(false,true)) {
            val snapshots=Channel<ReplySnapshot>(8);val spoken=mutableListOf<String>();val reports=mutableListOf<String>()
            val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
                {chunks->launch{for(chunk in chunks)spoken+=chunk}}, {}, reports::add,1)
            val job=launch{listener.run()}
            snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"原句。",false)),true))
            withTimeout(2000){while(spoken.isEmpty())delay(1)}
            snapshots.send(if(failed)ReplySnapshot(11,emptyList(),true,failed=true)
                else ReplySnapshot(10,listOf(ObservedReply(11,"改写。",false)),true))
            snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"改写。完成。")),false))
            delay(50);job.cancelAndJoin()
            assertEquals(listOf("原句。"),spoken)
        }
    }

    @Test fun canonicalSnapshotHidesPartialMarkupAndMatchesFinalHistory() {
        assertEquals("你好。",ReplyListener.visibleText("<think>私密推理</think>你好。<tool name='x'>秘密"))
        assertEquals("你好。 后续",ReplyListener.visibleText("你好。  后续"))
        assertEquals("你好。",ReplyListener.visibleText("你好。<tool"))
    }

    @Test fun channelUsesExistingTtsWhileProducerStillOpen() = runBlocking {
        val chunks=Channel<String>(8);val spoken=mutableListOf<String>()
        val flow=VoiceConversation(this,{error("no STT")},{_,_->error("no model")},spoken::add,{}, {})
        val job=flow.playReplyStream(chunks)
        chunks.send("第一句。")
        withTimeout(2000){while(spoken.isEmpty())delay(1)}
        assertTrue(job.isActive)
        chunks.send("最后一句。");chunks.close();job.join()
        assertEquals("第一句。最后一句。",spoken.joinToString(""))
    }
}
