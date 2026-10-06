package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionCheckTest {
    private fun settings() = MobileSettings(SpeechConfig.openAiDefaults().copy(sttKey = "test", ttsKey = "test"), chatId = "one")

    @Test fun missingFieldsPreventAllNetworkCalls() = runBlocking {
        var calls = 0
        val checker = ConnectionCheck({ calls++; emptyList() }, { calls++ }, { calls++ }, { calls++; true })
        val result = checker.run(settings().copy(speech = SpeechConfig.bailianDefaults())) {}
        assertEquals(0, calls)
        assertTrue(result.contains("识别 API Key"))
        assertTrue(result.contains("合成 API Key"))
    }

    @Test fun stagesAreIndependentAndNeverClaimChatReplyOrMicrophoneWasTested() = runBlocking {
        val calls = mutableListOf<String>()
        val updates = mutableListOf<String>()
        val checker = ConnectionCheck({ calls.add("chats"); listOf(OperitChat("gone", "")) },
            { calls.add("asr"); throw SpeechApiException("识别服务返回 HTTP 401。") },
            { calls.add("tts") }, { calls.add("judge"); true })
        val result = checker.run(settings(), updates::add)
        assertEquals(listOf("chats", "asr", "tts"), calls)
        assertTrue(result.contains("重新选择聊天"))
        assertTrue(result.contains("密钥"))
        assertTrue(result.contains("已收到测试音频"))
        assertFalse(result.contains("全部正常"))
        assertTrue(updates.size >= 3)
    }

    @Test fun unknownTransportErrorCannotLeakCredentials() = runBlocking {
        val checker = ConnectionCheck({ throw IllegalStateException("https://secret:password@example.org") },
            { throw IllegalStateException("Bearer private-key") }, {}, { true })
        val result = checker.run(settings()) {}
        assertFalse(result.contains("private-key"))
        assertFalse(result.contains("password"))
    }

    @Test fun cancellationStopsLaterChecks() = runBlocking {
        val began = CompletableDeferred<Unit>()
        var ttsCalls = 0
        val checker = ConnectionCheck({ listOf(OperitChat("one", "")) },
            { began.complete(Unit); awaitCancellation() }, { ttsCalls++ }, { true })
        val job = launch { checker.run(settings()) {} }
        began.await(); job.cancelAndJoin()
        assertEquals(0, ttsCalls)
    }

    @Test fun enabledJudgeIsCheckedAndTimeoutDoesNotSkipSpeech() = runBlocking {
        var judgeCalls = 0
        val checker = ConnectionCheck({ awaitCancellation() }, {}, {}, { judgeCalls++; false }, timeoutMillis = 30)
        val result = checker.run(settings().copy(smartEndpoint = true,
            endJudge = EndJudgeConfig(key = "test"))) {}
        assertTrue(result.contains("超时"))
        assertTrue(result.contains("已收到测试音频"))
        assertTrue(result.contains("判断接口已响应"))
        assertEquals(1, judgeCalls)
    }

    @Test fun manualReviewDoesNotRequireOrCallPaidSmartJudge() = runBlocking {
        var judgeCalls = 0
        val manual = settings().copy(smartEndpoint = true, endJudge = null, confirmBeforeSend = true)
        assertTrue(configurationIssues(manual).isEmpty())
        val checker = ConnectionCheck({ listOf(OperitChat("one", "聊天")) }, {}, {}, { judgeCalls++; true })
        val result = checker.run(manual) {}
        assertEquals(0, judgeCalls)
        assertFalse(result.contains("智能结束判断："))
        assertTrue(result.contains("语音识别"))
    }
}
