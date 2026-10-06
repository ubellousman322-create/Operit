package com.huigu.phone10.mobile

/** Geometry in density-independent screen coordinates. */
internal data class CaptionScreen(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal data class CaptionLayout(val x: Float = 12f, val y: Float = 250f,
    val width: Float = 286f, val height: Float = 198f) {
    fun visible(screen: CaptionScreen, expanded: Boolean, pendingReview: Boolean = false): CaptionLayout {
        val availableWidth = (screen.right - screen.left).coerceAtLeast(1f)
        val availableHeight = (screen.bottom - screen.top).coerceAtLeast(1f)
        val w = if (expanded) width.coerceIn(minOf(if (pendingReview) 260f else 180f, availableWidth), availableWidth)
            else minOf(if (pendingReview) 148f else 68f, availableWidth)
        val h = if (expanded) height.coerceIn(minOf(if (pendingReview) 280f else 124f, availableHeight), availableHeight)
            else minOf(44f, availableHeight)
        return CaptionLayout(x.coerceIn(screen.left, (screen.right - w).coerceAtLeast(screen.left)),
            y.coerceIn(screen.top, (screen.bottom - h).coerceAtLeast(screen.top)), w, h)
    }

    fun resizeBy(dx: Float, dy: Float, screen: CaptionScreen, pendingReview: Boolean = false): CaptionLayout {
        val shown = visible(screen, true, pendingReview)
        val availableWidth = (screen.right - shown.x).coerceAtLeast(1f)
        val availableHeight = (screen.bottom - shown.y).coerceAtLeast(1f)
        return shown.copy(width = (shown.width + dx).coerceIn(minOf(if (pendingReview) 260f else 180f, availableWidth), availableWidth),
            height = (shown.height + dy).coerceIn(minOf(if (pendingReview) 280f else 124f, availableHeight), availableHeight))
    }

    fun moveTo(x: Float, y: Float, screen: CaptionScreen, expanded: Boolean, pendingReview: Boolean = false): CaptionLayout {
        val shown = copy(x = x, y = y).visible(screen, expanded, pendingReview)
        return copy(x = shown.x, y = shown.y)
    }
}
