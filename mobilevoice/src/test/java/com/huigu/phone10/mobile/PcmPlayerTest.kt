package com.huigu.phone10.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PcmPlayerTest {
    @Test fun playbackLevelUsesTwentyMillisecondWindowsWithinOneLargeWrite() {
        val sink = Sink()
        val player = PcmPlayer(sink)
        val pcm = ByteArray(1_920) { if (it < 960 && it % 2 == 1) 64 else 0 }
        player.write(pcm)
        sink.played = 240
        assertTrue(player.playbackLevel() > 0.5f)
        sink.played = 720
        assertEquals(0f, player.playbackLevel(), 0.01f)
        player.close()
    }

    @Test fun levelReadAndPauseDoNotWaitForBackpressuredWriter() {
        val stalled = CountDownLatch(1)
        val sink = object : PcmSink {
            var calls = 0
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                calls++
                if (calls == 1) return minOf(length, 960)
                stalled.countDown()
                return 0
            }
            override fun playedFrames() = 240L
            override fun close() = Unit
        }
        val player = PcmPlayer(sink)
        val writer = Thread {
            try { player.write(ByteArray(9_600) { if (it % 2 == 1) 64 else 0 }) }
            catch (_: CancellationException) { }
        }
        val monitor = Executors.newSingleThreadExecutor()
        writer.start()
        try {
            assertTrue(stalled.await(1, TimeUnit.SECONDS))
            assertTrue(monitor.submit<Float> { player.playbackLevel() }.get(300, TimeUnit.MILLISECONDS) > 0.5f)
            player.setPaused(true)
            assertEquals(0f, monitor.submit<Float> { player.playbackLevel() }.get(300, TimeUnit.MILLISECONDS), 0f)
            player.close()
            assertEquals(0f, monitor.submit<Float> { player.playbackLevel() }.get(300, TimeUnit.MILLISECONDS), 0f)
        } finally {
            player.close()
            writer.join(1_000)
            monitor.shutdownNow()
        }
        assertFalse(writer.isAlive)
    }

    @Test fun longAudioKeepsOnlyBoundedLevelWindows() {
        val player = PcmPlayer(Sink())
        player.write(ByteArray(2_000_000))
        assertTrue(player.bufferedLevelWindowCount <= 1_000)
        player.close()
    }

    @Test fun glowLevelFollowsPlayedPcmRatherThanQueuedPcm() {
        val sink = Sink()
        val player = PcmPlayer(sink)
        val loud = ByteArray(200) { if (it % 2 == 0) 0 else 64 }
        player.write(loud)
        assertEquals(0f, player.playbackLevel(), 0.001f)
        sink.played = 20
        assertTrue(player.playbackLevel() > 0.5f)
        player.setPaused(true)
        assertEquals(0f, player.playbackLevel(), 0.001f)
        player.setPaused(false)
        sink.played = 100
        assertEquals(0f, player.playbackLevel(), 0.001f)
        player.close()
    }

    @Test fun sampleRateIsConfiguredBeforePlaybackAndCannotChangeMidAudio() {
        var configured = 24000
        val player = PcmPlayer(object : PcmSink {
            override fun configure(sampleRate: Int) { configured = sampleRate }
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                assertEquals(48000, configured)
                return length
            }
            override fun playedFrames() = 0L
            override fun close() = Unit
        })
        player.configure(48000)
        player.write(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { player.configure(24000) }
        player.close()
        assertThrows(CancellationException::class.java) { player.configure(48000) }
    }
    @Test fun focusPauseRetainsAudioAndResumeContinuesWithoutClosing() {
        val sink = Sink()
        val beforeWrite = CountDownLatch(1)
        val done = CountDownLatch(1)
        val player = PcmPlayer(sink) { beforeWrite.countDown() }
        player.setPaused(true)
        val thread = Thread { try { player.write(byteArrayOf(1, 2, 3, 4)) } finally { done.countDown() } }
        thread.start()
        assertTrue(beforeWrite.await(1, TimeUnit.SECONDS))
        assertFalse(done.await(40, TimeUnit.MILLISECONDS))
        assertEquals(0, sink.closes)
        player.setPaused(false)
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(listOf<Byte>(1, 2, 3, 4), sink.bytes)
        player.close()
    }

    @Test fun hangingUpUnblocksFocusPausedWriter() {
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val player = PcmPlayer(Sink()) { entered.countDown() }
        player.setPaused(true)
        val thread = Thread {
            try { player.write(byteArrayOf(1, 2)) }
            catch (_: CancellationException) { }
            finally { done.countDown() }
        }
        thread.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        player.close(); assertTrue(done.await(1, TimeUnit.SECONDS))
    }

    private class Sink : PcmSink {
        val bytes = mutableListOf<Byte>()
        @Volatile var played = 0L
        var closes = 0
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(2, length)
            bytes.addAll(data.copyOfRange(offset, offset + count).toList())
            return count
        }
        override fun playedFrames() = played
        override fun close() { closes++ }
    }

    @Test fun preservesOddBoundariesAndShortWrites() {
        val sink = Sink()
        val player = PcmPlayer(sink)
        player.write(byteArrayOf(1, 2, 3))
        player.write(byteArrayOf(4, 5, 6, 7, 8))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8), sink.bytes)
        player.close()
        player.close()
        assertEquals(1, sink.closes)
    }

    @Test fun drainWaitsUntilAllSubmittedFramesHaveActuallyPlayed() = runBlocking {
        val sink = Sink()
        val player = PcmPlayer(sink)
        player.write(byteArrayOf(1, 2, 3, 4))
        val drain = async { player.drain() }
        delay(40)
        assertFalse(drain.isCompleted)
        sink.played = 2
        withTimeout(500) { drain.await() }
        player.close()
    }

    @Test fun closingUnblocksAStalledWriter() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val player = PcmPlayer(object : PcmSink {
            override fun write(data: ByteArray, offset: Int, length: Int): Int { entered.countDown(); return 0 }
            override fun playedFrames() = 0L
            override fun close() = Unit
        })
        val worker = Thread { try { player.write(byteArrayOf(1, 2)) } catch (_: CancellationException) { } finally { exited.countDown() } }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        player.close()
        assertTrue(exited.await(1, TimeUnit.SECONDS))
    }

    @Test fun drainPrimesShortTailButWaitsOnlyForRealAudio() = runBlocking {
        val bytes = mutableListOf<Byte>()
        val player = PcmPlayer(object : PcmSink {
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                bytes.addAll(data.copyOfRange(offset, offset + length).toList())
                return length
            }
            override fun playedFrames() = if (bytes.size >= 6) 1L else 0L
            override fun endPaddingBytes() = 4
            override fun close() = Unit
        })
        player.write(byteArrayOf(1, 2))
        try { withTimeout(500) { player.drain() } } finally { player.close() }
        assertEquals(listOf<Byte>(1, 2, 0, 0, 0, 0), bytes)
    }

    @Test fun incompleteSampleFailsInsteadOfSilentlyDroppingTheByte() = runBlocking {
        val player = PcmPlayer(Sink())
        player.write(byteArrayOf(1))
        try {
            assertThrows(IllegalStateException::class.java) { runBlocking { player.drain() } }
        } finally { player.close() }
        Unit
    }

    @Test fun closeCancelsPendingDrain() = runBlocking {
        val player = PcmPlayer(Sink())
        player.write(byteArrayOf(1, 2))
        val draining = async { player.drain() }
        yield()
        player.close()
        try { withTimeout(500) { draining.await() }; fail("Closed playback reported completion") }
        catch (_: CancellationException) { }
    }
}
