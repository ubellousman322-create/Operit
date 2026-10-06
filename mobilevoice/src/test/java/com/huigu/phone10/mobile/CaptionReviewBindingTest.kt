package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class CaptionReviewBindingTest {
    private class WindowSurface : CaptionReviewBinding.Surface {
        var visibleEditorText = ""
        var scrollPosition = 0
        private val afterLayout = mutableListOf<() -> Unit>()
        private val posted = mutableListOf<() -> Unit>()

        override fun editorText() = visibleEditorText
        override fun showEditorText(value: String) { visibleEditorText = value }
        override fun afterLayout(action: () -> Unit) { afterLayout += action }
        override fun post(action: () -> Unit) { posted += action }
        override fun scrollToReview() { scrollPosition = 0 }
        override fun scrollToCaptionEnd() { scrollPosition = 999 }

        fun completeLayout() { afterLayout.toList().also { afterLayout.clear() }.forEach { it() } }
        fun runPosted() { posted.toList().also { posted.clear() }.forEach { it() } }
    }

    @Test fun draftRenderedBeforeWindowCreationAppearsInFirstEditorAndAfterRecreation() {
        val binding = CaptionReviewBinding()
        binding.render(PendingVoiceDraft("one", "真正要发的字"), editing = false)

        val firstWindow = WindowSurface()
        binding.attach(firstWindow)
        firstWindow.completeLayout()
        assertEquals("真正要发的字", firstWindow.visibleEditorText)

        binding.review.userEdited("改好以后")
        binding.render(PendingVoiceDraft("one", "改好以后"), editing = true)
        binding.detach()
        val reopenedWindow = WindowSurface()
        binding.attach(reopenedWindow)
        reopenedWindow.completeLayout()
        assertEquals("改好以后", reopenedWindow.visibleEditorText)
        assertTrue(binding.review.canSend)
    }

    @Test fun newDraftBeatsQueuedSubtitleAutoScrollAndRevealReturnsToEditor() {
        val binding = CaptionReviewBinding()
        val window = WindowSurface()
        binding.attach(window)
        window.scrollPosition = 999
        binding.followCaptionAtBottom()

        binding.render(PendingVoiceDraft("two", "这句待确认"), editing = false)
        window.runPosted()
        window.completeLayout()
        assertEquals(0, window.scrollPosition)
        assertEquals("这句待确认", window.visibleEditorText)

        window.scrollPosition = 80
        binding.render(PendingVoiceDraft("two", "这句待确认"), editing = false)
        window.completeLayout()
        assertEquals(80, window.scrollPosition)

        binding.reveal()
        window.completeLayout()
        assertEquals(0, window.scrollPosition)
    }

    @Test fun oldWindowCallbacksCannotMoveOrRewriteARecreatedWindow() {
        val binding = CaptionReviewBinding()
        val oldWindow = WindowSurface()
        binding.attach(oldWindow)
        binding.followCaptionAtBottom()
        binding.detach()

        binding.render(PendingVoiceDraft("new", "新窗口草稿"), editing = false)
        val currentWindow = WindowSurface()
        currentWindow.scrollPosition = 999
        binding.attach(currentWindow)
        oldWindow.runPosted()
        oldWindow.completeLayout()
        currentWindow.completeLayout()

        assertEquals(0, currentWindow.scrollPosition)
        assertEquals("新窗口草稿", currentWindow.visibleEditorText)
    }
}
