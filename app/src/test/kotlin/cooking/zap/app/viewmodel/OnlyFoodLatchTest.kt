package cooking.zap.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for the OnlyFood cold-start/resume latch rule ([shouldLatchLoaded]).
 * The live `submit()` path needs Android ([cooking.zap.app.relay.RelayPool] ->
 * OkHttp/SharedPreferences) and is exercised on-device; the DECISION that turns
 * a query outcome into the `loaded` latch — the part that distinguishes "empty
 * but reached EOSE" from "failed to load" — is pure and tested here.
 */
class OnlyFoodLatchTest {

    @Test
    fun genuineEose_latches_evenWithZeroEvents() {
        // EOSE arrived on a live, sent query → the window is genuinely empty;
        // latch so toggling/auto-recover won't re-query (and re-throttle) it.
        assertTrue(shouldLatchLoaded(connected = true, anySent = true, eoseFired = true))
    }

    @Test
    fun eoseTimeout_doesNotLatch() {
        // Connected and sent, but EOSE never came (8s timeout) — a transient
        // failure, not an empty window. Must stay un-latched to retry.
        assertFalse(shouldLatchLoaded(connected = true, anySent = true, eoseFired = false))
    }

    @Test
    fun droppedSend_doesNotLatch() {
        // sendToRelayOrEphemeral returned false for every REQ (cooldown / no
        // capacity). Nothing was asked, so an EOSE can't be real — never latch.
        assertFalse(shouldLatchLoaded(connected = true, anySent = false, eoseFired = false))
        // Even if an unrelated EOSE for the base prefix were observed, a query we
        // never sent must not be treated as loaded.
        assertFalse(shouldLatchLoaded(connected = true, anySent = false, eoseFired = true))
    }

    @Test
    fun connectTimeout_doesNotLatch() {
        // The socket never came up, so we never sent — leave it un-latched for
        // auto-recover/refresh to retry once the relay (re)connects.
        assertFalse(shouldLatchLoaded(connected = false, anySent = false, eoseFired = false))
    }
}
