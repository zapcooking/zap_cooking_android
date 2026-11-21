package cooking.zap.app.ui.util

import cooking.zap.app.nostr.ProfileData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [noteZapOfferable] — the note-level zap affordance gate
 * mirroring SocialActionManager's send-time guard ("This user has no
 * lightning address"): the affordance is offered only when the note author
 * advertises a NIP-57 lud16. Covers the exact cases the send guard accepts
 * or rejects so the UI never offers a zap that must fail.
 */
class NoteZapOfferableTest {

    private fun profile(lud16: String?) = ProfileData(
        pubkey = "a".repeat(64),
        name = null,
        displayName = null,
        about = null,
        picture = null,
        banner = null,
        nip05 = null,
        lud16 = lud16,
        updatedAt = 0L
    )

    @Test
    fun `author with lightning address is offered`() {
        assertTrue(noteZapOfferable(profile("author@getalby.com")))
    }

    @Test
    fun `null lightning address is not offered`() {
        assertFalse(noteZapOfferable(profile(null)))
    }

    @Test
    fun `blank lightning address is not offered`() {
        assertFalse(noteZapOfferable(profile("")))
        assertFalse(noteZapOfferable(profile("   ")))
    }

    @Test
    fun `missing profile is not offered`() {
        assertFalse(noteZapOfferable(null))
    }
}
