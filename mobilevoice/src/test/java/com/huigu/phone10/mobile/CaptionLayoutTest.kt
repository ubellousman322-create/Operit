package com.huigu.phone10.mobile

import org.junit.Assert.*
import org.junit.Test

class CaptionLayoutTest {
    private val screen = CaptionScreen(0f, 24f, 400f, 776f)

    @Test fun draggingCornerChangesBothDimensionsWithoutMovingOrigin() {
        val original = CaptionLayout(12f, 250f, 286f, 198f)
        val changed = original.resizeBy(40f, 60f, screen)
        assertEquals(CaptionLayout(12f, 250f, 326f, 258f), changed)
    }

    @Test fun extremeResizeKeepsWindowReadableAndInsideScreen() {
        val original = CaptionLayout(100f, 400f, 240f, 198f)
        assertEquals(CaptionLayout(100f, 400f, 180f, 124f), original.resizeBy(-1000f, -1000f, screen))
        assertEquals(CaptionLayout(100f, 400f, 300f, 376f), original.resizeBy(1000f, 1000f, screen))
    }

    @Test fun collapsingAndRotatingDoNotOverwriteRememberedExpandedSize() {
        val original = CaptionLayout(12f, 250f, 350f, 400f)
        val collapsed = original.visible(screen, false)
        assertEquals(68f, collapsed.width, 0f)
        assertEquals(44f, collapsed.height, 0f)
        val landscape = original.visible(CaptionScreen(30f, 0f, 780f, 360f), true)
        assertTrue(landscape.x >= 30f)
        assertTrue(landscape.y + landscape.height <= 360f)
        assertEquals(original, original.visible(screen, true))
    }

    @Test fun movingCollapsedWindowAndExpandingKeepsControlsReachable() {
        val moved = CaptionLayout().moveTo(900f, 900f, screen, false)
        assertEquals(332f, moved.x, 0f)
        assertEquals(732f, moved.y, 0f)
        val expanded = moved.visible(screen, true)
        assertTrue(expanded.x + expanded.width <= 400f)
        assertTrue(expanded.y + expanded.height <= 776f)
        assertTrue(expanded.y >= 24f)
    }

    @Test fun pendingReviewKeepsEditorAndButtonsReachableAfterResizeAndRotation() {
        val small = CaptionLayout(100f, 500f, 190f, 124f)
            .resizeBy(-1000f, -1000f, screen, pendingReview = true)
        assertEquals(260f, small.width, 0f)
        assertEquals(280f, small.height, 0f)
        val landscape = small.visible(CaptionScreen(0f, 0f, 560f, 310f),
            expanded = true, pendingReview = true)
        assertTrue(landscape.y + landscape.height <= 310f)
        assertTrue(landscape.height >= 280f)
        val collapsed = small.visible(screen, expanded = false, pendingReview = true)
        assertTrue(collapsed.width >= 148f)
        assertEquals(44f, collapsed.height, 0f)
    }
}
