package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ReplyLongTest {
    @Test fun longStreamingReplyDeliversOnlyNewTailAndDoesNotExposeHiddenText() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(4);val spoken=StringBuilder()
        val prefix="长篇正文。".repeat(14000)
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {chunks->launch{for(chunk in chunks)spoken.append(chunk)}}, {}, {},1)
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"<think>隐藏</think>"+prefix,false)),true))
        withTimeout(5000){while(spoken.length<prefix.length)delay(1)}
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,"<think>隐藏</think>"+prefix+"完整尾句。")),false))
        withTimeout(5000){while(!spoken.endsWith("完整尾句。"))delay(1)}
        job.cancelAndJoin();assertEquals(prefix+"完整尾句。",spoken.toString())
    }

    @Test fun stopWhileQueueIsFullSuppressesBacklogAndLaterReplyStillPlays() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(4);val spoken=mutableListOf<String>();val diagnostics=mutableListOf<String>()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {chunks->launch{for(chunk in chunks){spoken+=chunk;if(chunk=="消息11。")awaitCancellation()}}},
            {}, {},1,diagnostics::add)
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(45,(11L..45L).map{ObservedReply(it,"消息$it。")},false))
        withTimeout(5000){while(!diagnostics.contains("reply_listener_backpressure_queue") || spoken.isEmpty())delay(1)}
        listener.interrupt()
        snapshots.send(ReplySnapshot(46,listOf(ObservedReply(46,"新的回复。")),false))
        withTimeout(5000){while(!spoken.contains("新的回复。"))delay(1)}
        assertTrue(job.isActive);job.cancelAndJoin();assertEquals(listOf("消息11。","新的回复。"),spoken)
    }

    @Test fun pendingCharacterBudgetWaitsWithoutDroppingTwoLongReplies() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(4);val spoken=StringBuilder();val diagnostics=mutableListOf<String>()
        val release=CompletableDeferred<Unit>();val text="字".repeat(400000)
        var plays=0
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {chunks->launch{if(plays++==0)release.await();for(chunk in chunks)spoken.append(chunk)}},
            {}, {},1,diagnostics::add)
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,text)),false))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,text)),false))
        withTimeout(5000){while(!diagnostics.contains("reply_listener_backpressure_chars"))delay(1)}
        release.complete(Unit)
        withTimeout(5000){while(spoken.length<text.length*2)delay(1)}
        assertTrue(job.isActive);job.cancelAndJoin();assertEquals(text+text,spoken.toString())
    }

    @Test fun oversizedMessageNoticeStopsItsPartialOnlyAndKeepsNextReply() = runBlocking {
        val snapshots=Channel<ReplySnapshot>(4);val spoken=mutableListOf<String>();val diagnostics=mutableListOf<String>()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {chunks->launch{for(chunk in chunks)spoken+=chunk}}, {}, {},1,diagnostics::add)
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(10,listOf(ObservedReply(11,"已播片段。",false)),true))
        withTimeout(5000){while(spoken.isEmpty())delay(1)}
        snapshots.send(ReplySnapshot(11,emptyList(),true,notice="MESSAGE_TOO_LARGE"))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,"下一条。")),false))
        withTimeout(5000){while(!spoken.contains("下一条。"))delay(1)}
        assertTrue(job.isActive);job.cancelAndJoin()
        assertEquals(listOf("已播片段。","下一条。"),spoken)
        assertEquals(listOf("reply_listener_message_too_large"),diagnostics.filter { it.startsWith("reply_listener_") })
    }

    @Test fun longReplyAndNextReplyAreSpokenWithoutTerminatingListener() = runBlocking {
        val text="长篇。".repeat(24000)
        val snapshots=Channel<ReplySnapshot>(4); val spoken=StringBuilder()
        val listener=ReplyListener({after->if(after==null)ReplySnapshot(10,emptyList(),false) else snapshots.receive()},
            {chunks->launch{for(chunk in chunks)spoken.append(chunk)}}, {}, {}, 1)
        val job=launch{listener.run()}
        snapshots.send(ReplySnapshot(11,listOf(ObservedReply(11,text)),false))
        snapshots.send(ReplySnapshot(12,listOf(ObservedReply(12,"最后一条。")),false))
        withTimeout(5000){while(!spoken.endsWith("最后一条。"))delay(1)}
        assertTrue(job.isActive);job.cancelAndJoin()
        assertEquals(text+"最后一条。",spoken.toString())
    }

    @Test fun moreThanSixteenRepliesWaitForPlaybackAndKeepOrder() = runBlocking {
        val spoken=mutableListOf<String>();var first=true
        val expected=(11L..45L).map{ "消息$it。" }
        val listener=ReplyListener({after->
            if(after==null)ReplySnapshot(10,emptyList(),false)
            else if(first){first=false;ReplySnapshot(45,(11L..45L).map{ObservedReply(it,"消息$it。")},false)}
            else ReplySnapshot(45,emptyList(),false)
        }, {chunks->launch{delay(5);for(chunk in chunks)spoken+=chunk}}, {}, {},1)
        val job=launch{listener.run()}
        withTimeout(5000){while(spoken.size<expected.size)delay(1)}
        assertTrue(job.isActive);job.cancelAndJoin();assertEquals(expected,spoken)
    }
}
