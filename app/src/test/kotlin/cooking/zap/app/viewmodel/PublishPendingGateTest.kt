package cooking.zap.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for "is a publish still in flight?" ([isPublishPending]). While it holds, the
 * composer's auto-save on leave is deferred — no cache write, no relay publish, no "Draft saved"
 * for a post that's about to go out. Any one signal is enough; only all-clear lets a save through.
 */
class PublishPendingGateTest {

    @Test
    fun `nothing in flight lets the save through`() {
        assertFalse(isPublishPending(publishing = false, countdownActive = false, pendingPublishQueued = false))
    }

    @Test
    fun `undo countdown running is pending`() {
        // Backing out mid-countdown: the canonical phantom-toast case.
        assertTrue(isPublishPending(publishing = true, countdownActive = true, pendingPublishQueued = true))
    }

    @Test
    fun `countdown job alone is pending`() {
        assertTrue(isPublishPending(publishing = false, countdownActive = true, pendingPublishQueued = false))
    }

    @Test
    fun `queued publish alone is pending`() {
        assertTrue(isPublishPending(publishing = false, countdownActive = false, pendingPublishQueued = true))
    }

    @Test
    fun `publish in flight without a timer is pending`() {
        // No-timer publish awaiting relays, or the PoW hand-off: countdown signals are clear but
        // the publish hasn't settled.
        assertTrue(isPublishPending(publishing = true, countdownActive = false, pendingPublishQueued = false))
    }
}
