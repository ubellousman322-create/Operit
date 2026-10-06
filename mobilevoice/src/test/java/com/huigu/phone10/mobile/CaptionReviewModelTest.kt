package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class CaptionReviewModelTest {
    @Test fun repeatedServiceRenderDoesNotReplaceFocusedCompositionOrSelection() {
        val model = CaptionReviewModel()
        assertEquals("识别正文", model.render(PendingVoiceDraft("one", "识别正文"), editing = false).editorText)
        model.userEdited("识别正文改")
        assertNull(model.render(PendingVoiceDraft("one", "识别正文"), editing = true).editorText)
        assertNull(model.render(PendingVoiceDraft("one", "识别正文改"), editing = true).editorText)
        assertEquals("识别正文改", model.currentText)
        assertTrue(model.canSend)
    }

    @Test fun newDraftAndCancellationReplaceOnlyTheirOwnProjection() {
        val model = CaptionReviewModel()
        assertTrue(model.render(PendingVoiceDraft("one", "原话"), editing = false).newDraft)
        model.userEdited("临时改字")
        val next = model.render(PendingVoiceDraft("two", "第二句话"), editing = true)
        assertTrue(next.newDraft)
        assertEquals("第二句话", next.editorText)
        assertEquals("two", model.draftId)
        model.render(null, editing = true)
        assertNull(model.draftId)
        assertFalse(model.canSend)
    }

    @Test fun unfocusedProjectionAcceptsLatestAuthoritativeTextAndBlocksBlankSend() {
        val model = CaptionReviewModel()
        model.render(PendingVoiceDraft("one", "初稿"), editing = false)
        model.userEdited("本地修改")
        assertEquals("另一端修改", model.render(PendingVoiceDraft("one", "另一端修改"), editing = false).editorText)
        model.userEdited(" \n ")
        assertFalse(model.canSend)
        assertEquals("one", model.draftId)
    }

    @Test fun overlongRecognitionMustBeShortenedBeforeOverlaySend() {
        val model = CaptionReviewModel()
        val longRecognition = "字".repeat(4001)
        assertEquals(longRecognition,
            model.render(PendingVoiceDraft("long", longRecognition), editing = false).editorText)
        assertFalse(model.canSend)
        model.userEdited("字".repeat(4000))
        assertTrue(model.canSend)
    }
}
