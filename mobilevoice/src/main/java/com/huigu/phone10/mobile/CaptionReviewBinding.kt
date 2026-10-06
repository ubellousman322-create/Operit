package com.huigu.phone10.mobile

/** Binds the service draft projection to whichever caption window is attached now. */
internal class CaptionReviewBinding(val review: CaptionReviewModel = CaptionReviewModel()) {
    interface Surface {
        fun editorText(): String
        fun showEditorText(value: String)
        fun afterLayout(action: () -> Unit)
        fun post(action: () -> Unit)
        fun scrollToReview()
        fun scrollToCaptionEnd()
    }

    private var surface: Surface? = null
    private var revision = 0L

    fun attach(next: Surface) {
        if (surface === next) return
        surface = next
        revision++
        if (review.draftId != null) {
            bindText(review.currentText)
            reveal()
        }
    }

    fun detach() {
        surface = null
        revision++
    }

    fun render(draft: PendingVoiceDraft?, editing: Boolean): CaptionReviewModel.Render {
        val changed = review.render(draft, editing)
        if (changed.newDraft || draft == null) revision++
        changed.editorText?.let(::bindText)
        if (changed.newDraft) reveal()
        return changed
    }

    fun reveal() {
        val target = surface ?: return
        val id = review.draftId ?: return
        val expectedRevision = ++revision
        target.afterLayout {
            if (surface === target && review.draftId == id && revision == expectedRevision)
                target.scrollToReview()
        }
    }

    fun followCaptionAtBottom() {
        if (review.draftId != null) return
        val target = surface ?: return
        val expectedRevision = revision
        target.post {
            if (surface === target && review.draftId == null && revision == expectedRevision)
                target.scrollToCaptionEnd()
        }
    }

    private fun bindText(value: String) {
        val target = surface ?: return
        if (target.editorText() != value) target.showEditorText(value)
    }
}
