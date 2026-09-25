package cooking.zap.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure gate for what a settling publish does to the editor ([publishCompletionAction]). The
 * published draft is always tombstoned by its owned id; this covers only the editor. A publish
 * that settles after the user moved on (reopened the composer, opened another draft) must leave
 * that new work alone — except to drop the published draft's own coordinate if it resurfaced.
 */
class PublishCompletionTest {

    @Test
    fun `same session resets the editor`() {
        assertEquals(
            PublishCompletion.RESET_EDITOR,
            publishCompletionAction(sessionMatches = true, ownedDraftId = "d1", currentDraftId = "d1")
        )
    }

    @Test
    fun `same session resets even with no owned draft`() {
        // A fresh post that was never saved as a draft.
        assertEquals(
            PublishCompletion.RESET_EDITOR,
            publishCompletionAction(sessionMatches = true, ownedDraftId = null, currentDraftId = null)
        )
    }

    @Test
    fun `new session showing the published draft detaches it`() {
        assertEquals(
            PublishCompletion.DETACH_DRAFT,
            publishCompletionAction(sessionMatches = false, ownedDraftId = "d1", currentDraftId = "d1")
        )
    }

    @Test
    fun `new session on a different draft is left alone`() {
        // Opened another draft from the Drafts list mid-countdown.
        assertEquals(
            PublishCompletion.LEAVE_EDITOR,
            publishCompletionAction(sessionMatches = false, ownedDraftId = "d1", currentDraftId = "d2")
        )
    }

    @Test
    fun `new session with nothing saved yet is left alone`() {
        // Reopened composer, typing new text that hasn't been saved.
        assertEquals(
            PublishCompletion.LEAVE_EDITOR,
            publishCompletionAction(sessionMatches = false, ownedDraftId = "d1", currentDraftId = null)
        )
    }

    @Test
    fun `new session when the publish owned no draft is left alone`() {
        assertEquals(
            PublishCompletion.LEAVE_EDITOR,
            publishCompletionAction(sessionMatches = false, ownedDraftId = null, currentDraftId = "d2")
        )
        assertEquals(
            PublishCompletion.LEAVE_EDITOR,
            publishCompletionAction(sessionMatches = false, ownedDraftId = null, currentDraftId = null)
        )
    }
}
