package cooking.zap.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure restore gate for a pending publish's own draft ([restoreSkipsPendingPublishDraft]). A
 * composer reopened mid-countdown must not auto-restore the post that's about to go out; any
 * other draft restores as usual, and nothing is skipped once no publish is pending.
 */
class PendingPublishRestoreSkipTest {

    @Test
    fun `pending publish's own draft is skipped`() {
        assertTrue(restoreSkipsPendingPublishDraft(dTag = "d1", pendingPublishDraftId = "d1"))
    }

    @Test
    fun `a different draft still restores`() {
        assertFalse(restoreSkipsPendingPublishDraft(dTag = "d2", pendingPublishDraftId = "d1"))
    }

    @Test
    fun `nothing pending skips nothing`() {
        // Settled (published or undone): the next open restores normally.
        assertFalse(restoreSkipsPendingPublishDraft(dTag = "d1", pendingPublishDraftId = null))
    }

    @Test
    fun `a pending fresh post owns no draft and skips nothing`() {
        assertFalse(restoreSkipsPendingPublishDraft(dTag = "d1", pendingPublishDraftId = null))
        assertFalse(restoreSkipsPendingPublishDraft(dTag = null, pendingPublishDraftId = null))
    }

    @Test
    fun `a cache with no id is never mistaken for the pending draft`() {
        // Legacy cache entries carry no id.
        assertFalse(restoreSkipsPendingPublishDraft(dTag = null, pendingPublishDraftId = "d1"))
    }
}
