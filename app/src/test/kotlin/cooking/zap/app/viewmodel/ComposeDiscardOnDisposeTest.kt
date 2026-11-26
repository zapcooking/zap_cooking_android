package cooking.zap.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for the composer's discard-on-dispose decision ([shouldDiscardOnDispose]). This is
 * the crux of the phantom-draft fix's PR 2: emptying a restored draft and leaving must discard it,
 * but the trigger has to be narrow — a blank reply composer or an un-restored composer must never
 * wipe the cache. The live path (LastDraftCache prefs + relay replacement) needs Android and is
 * exercised on-device; the DECISION is pure and tested here.
 */
class ComposeDiscardOnDisposeTest {

    @Test
    fun `emptied restored top-level draft discards`() {
        // User cleared the text they could see; the editor was showing the restored draft.
        assertTrue(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                restoredDraftId = "draft-abc",
                textIsBlank = true,
                mediaIsEmpty = true
            )
        )
    }

    @Test
    fun `blank editor with attachments still up does not discard`() {
        // Attachment slots are content too — emptying the prose but keeping the
        // images leaves a live draft the auto-save path owns.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                restoredDraftId = "draft-abc",
                textIsBlank = true,
                mediaIsEmpty = false
            )
        )
    }

    @Test
    fun `blank reply composer does not discard cached top-level draft`() {
        // A reply composer may sit over an unrelated cached top-level draft the user never opened;
        // leaving it blank must not destroy that draft.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = false,
                currentDraftId = "draft-abc",
                restoredDraftId = "draft-abc",
                textIsBlank = true,
                mediaIsEmpty = true
            )
        )
    }

    @Test
    fun `blank top-level composer with nothing restored does not discard`() {
        // Nothing was restored this session (currentDraftId null) — an empty composer the user
        // opened and closed must leave any cached draft alone.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = null,
                restoredDraftId = "draft-abc",
                textIsBlank = true,
                mediaIsEmpty = true
            )
        )
    }

    @Test
    fun `editor draft id not matching the restored one does not discard`() {
        // The editor isn't showing the auto-restored draft (ids diverged) — leave it alone.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                restoredDraftId = "draft-XYZ",
                textIsBlank = true,
                mediaIsEmpty = true
            )
        )
    }

    @Test
    fun `emptied relay-restored draft discards even with an empty cache`() {
        // Slow-path restore (draft fetched from relays, e.g. written on another device) never
        // fills LastDraftCache. The old cache-id gate saw cachedId == null here and never
        // discarded, so the emptied draft kept coming back. restoredDraftId is set by both paths.
        assertTrue(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-from-relay",
                restoredDraftId = "draft-from-relay",
                textIsBlank = true,
                mediaIsEmpty = true
            )
        )
    }

    @Test
    fun `emptied draft opened from the drafts list does not discard`() {
        // A deliberate pick from the Drafts list is never marked restored; emptying it and
        // backing out leaves it in the list.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-picked",
                restoredDraftId = null,
                textIsBlank = true,
                mediaIsEmpty = true
            )
        )
    }

    @Test
    fun `non-blank text does not discard`() {
        // The user still has content; the non-blank auto-save path owns that, not discard.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                restoredDraftId = "draft-abc",
                textIsBlank = false,
                mediaIsEmpty = true
            )
        )
    }
}
