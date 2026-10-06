package com.huigu.phone10.mobile

/** Ephemeral editor projection. VoiceConversation remains the only draft owner. */
internal class CaptionReviewModel {
    data class Render(val editorText: String? = null, val newDraft: Boolean = false)

    var draftId: String? = null
        private set
    var currentText: String = ""
        private set
    val canSend: Boolean get() = draftId != null && currentText.isNotBlank() && currentText.length <= 4000

    fun render(draft: PendingVoiceDraft?, editing: Boolean): Render {
        if (draft == null) {
            draftId = null
            currentText = ""
            return Render()
        }
        val newDraft = draft.id != draftId
        draftId = draft.id
        val update = newDraft || (!editing && currentText != draft.text)
        if (update) currentText = draft.text
        return Render(if (update) draft.text else null, newDraft)
    }

    fun userEdited(text: String) { if (draftId != null) currentText = text }
}
