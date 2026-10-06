package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class CaptionReviewPolicyTest {
    @Test fun pendingDraftShowsExistingWindowWithoutChangingCaptionPreference() {
        assertTrue(CaptionReviewPolicy.shouldShow(captionsEnabled = false, pendingReview = true))
        assertTrue(CaptionReviewPolicy.shouldShow(captionsEnabled = true, pendingReview = false))
        assertFalse(CaptionReviewPolicy.shouldShow(captionsEnabled = false, pendingReview = false))
    }

    @Test fun reviewEntryNeverTreatsMissingOverlayPermissionAsAnEditor() {
        assertEquals(CaptionReviewPolicy.Entry.PANEL,
            CaptionReviewPolicy.entry(pendingReview = true, overlayAllowed = true))
        assertEquals(CaptionReviewPolicy.Entry.PERMISSION,
            CaptionReviewPolicy.entry(pendingReview = true, overlayAllowed = false))
        assertEquals(CaptionReviewPolicy.Entry.NONE,
            CaptionReviewPolicy.entry(pendingReview = false, overlayAllowed = true))
    }
}
