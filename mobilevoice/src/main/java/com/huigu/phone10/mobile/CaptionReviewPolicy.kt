package com.huigu.phone10.mobile

internal object CaptionReviewPolicy {
    enum class Entry { NONE, PANEL, PERMISSION }

    fun shouldShow(captionsEnabled: Boolean, pendingReview: Boolean): Boolean =
        captionsEnabled || pendingReview

    fun entry(pendingReview: Boolean, overlayAllowed: Boolean): Entry = when {
        !pendingReview -> Entry.NONE
        overlayAllowed -> Entry.PANEL
        else -> Entry.PERMISSION
    }
}
