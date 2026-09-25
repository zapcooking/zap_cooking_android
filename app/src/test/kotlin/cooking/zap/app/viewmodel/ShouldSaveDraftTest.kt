package cooking.zap.app.viewmodel

import cooking.zap.app.ui.component.ComposerMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for the composer's auto-save on leave ([shouldSaveDraft]). Backing out of a restored
 * draft the user never touched must not republish it or flash "Draft saved"; any real edit —
 * prose, attachments, or alt text — must still save.
 */
class ShouldSaveDraftTest {

    @Test
    fun `new draft saves`() {
        assertTrue(shouldSaveDraft(content = "hello", currentDraftId = null, lastPersistedContent = null))
    }

    @Test
    fun `restored draft left unchanged does not save`() {
        val restored = draftFingerprint("hello", emptyList())
        assertFalse(shouldSaveDraft(content = restored, currentDraftId = "d1", lastPersistedContent = restored))
    }

    @Test
    fun `restored draft edited saves`() {
        val restored = draftFingerprint("hello", emptyList())
        val edited = draftFingerprint("hello world", emptyList())
        assertTrue(shouldSaveDraft(content = edited, currentDraftId = "d1", lastPersistedContent = restored))
    }

    @Test
    fun `draft id without a baseline saves`() {
        assertTrue(shouldSaveDraft(content = "hello", currentDraftId = "d1", lastPersistedContent = null))
    }

    @Test
    fun `attachment edits change the fingerprint with prose unchanged`() {
        val a = ComposerMedia(url = "https://h/a.jpg")
        val b = ComposerMedia(url = "https://h/b.jpg")
        val base = draftFingerprint("caption", listOf(a, b))
        assertNotEquals(base, draftFingerprint("caption", listOf(a)))            // removed
        assertNotEquals(base, draftFingerprint("caption", listOf(b, a)))         // reordered
        assertNotEquals(base, draftFingerprint("caption", listOf(a.copy(alt = "a dish"), b))) // described
    }

    @Test
    fun `late metadata fill is not an edit`() {
        val bare = ComposerMedia(url = "https://h/a.jpg")
        val filled = bare.copy(mimeType = "image/jpeg", dimensions = "800x600", thumbhash = "abc")
        assertFalse(
            shouldSaveDraft(
                content = draftFingerprint("x", listOf(filled)),
                currentDraftId = "d1",
                lastPersistedContent = draftFingerprint("x", listOf(bare))
            )
        )
    }

    @Test
    fun `prose is not confused with an attachment boundary`() {
        // Separators are control characters, so text can't impersonate a slot list.
        assertNotEquals(
            draftFingerprint("a", listOf(ComposerMedia(url = "b"))),
            draftFingerprint("ab", emptyList())
        )
    }
}
