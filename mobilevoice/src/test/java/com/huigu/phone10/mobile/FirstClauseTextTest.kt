package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class FirstClauseTextTest {
    @Test fun firstCommaImmediatelyEmitsTinyGreetingThenCombinesLaterClauses() {
        val text = SpeechText(firstClauseMode = true)
        assertTrue(text.push("小").isEmpty())
        assertEquals(listOf("小盈，"), text.push("盈，"))
        assertTrue(text.push("我在。慢慢来，别着急。").isEmpty())
        assertEquals(listOf("我在。慢慢来，别着急。"), text.flush())
        assertTrue(text.flush().isEmpty())
    }

    @Test fun noEarlyTimerFragmentBeforeFirstPunctuationAndToolPauseFlushesTail() {
        var now = 0L
        val text = SpeechText(firstClauseMode = true) { now }
        text.push("我来看看")
        now = 150
        assertTrue(text.flushReady().isEmpty())
        assertEquals(listOf("我来看看。"), text.push("。"))
        text.push("稍等我查一下")
        now = 1649
        assertTrue(text.flushReady().isEmpty())
        now = 1650
        assertEquals(listOf("稍等我查一下"), text.flushReady())
        assertEquals(listOf("查好了。"), text.run { push("查好了。"); flush() })
    }

    @Test fun longTextIsBoundedWithoutLosingOrDuplicatingCharactersOrSplittingEmoji() {
        val text = SpeechText(preserveSpacing = true, firstClauseMode = true)
        val body = "你好，" + "中".repeat(239) + "😀" + "文".repeat(521) + "。"
        val chunks = body.toList().flatMap { text.push(it.toString()) } + text.flush()
        assertEquals(body, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 241 })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun timerNeverSubmitsHalfOfEmojiAndHiddenToolMarkupIsNotRead() {
        var now = 0L
        val text = SpeechText(firstClauseMode = true) { now }
        assertEquals(listOf("好，"), text.push("<think>secret.</think>好，"))
        assertTrue(text.push("<tool>secret</tool>😀".dropLast(1)).isEmpty())
        now = 1500
        assertTrue(text.flushReady().isEmpty())
        text.push("\uDE00然后继续")
        assertEquals(listOf("😀然后继续"), text.flush())
    }
}
