package cooking.zap.app.relay

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads events from a NIP-42 AUTH-required relay, surviving reconnects and the
 * resync race (concern 2.4a). **Reusable** — Nourish read now, Nourish compute
 * (2.4b) and future members-only reads.
 *
 * NIP-42 auth is per-connection, so the read can't trust a stale URL-keyed
 * "authenticated" flag (and [RelayPool] now clears it on disconnect, so
 * `isAuthenticated` reflects the *live* socket). The read sends the REQ and
 * watches the relay's frames:
 *  - `EOSE` → done; collect the events.
 *  - `CLOSED <sub> auth-required` → the live socket wasn't authed yet (cold
 *    start, or a resync re-sent the REQ on a fresh socket before re-auth) →
 *    wait for the auto-AUTH to land (RelayPool auto-signs pre-approved relays)
 *    and re-send, bounded to [maxAttempts].
 *  - timeout while [RelayPool.reconnectGeneration] changed → a reconnect raced
 *    the query → retry; otherwise return what we have (genuine slow/no-score).
 *
 * The relay is pinned against ephemeral eviction for the read. Returns the
 * collected events (empty on exhausted retries / can't-auth / timeout). The
 * caller gates on a signing key — READ_ONLY can't auth.
 */
class AuthedRelayReader(private val relayPool: RelayPool) {

    private val seq = AtomicLong(0)

    suspend fun read(
        url: String,
        filter: Filter,
        authTimeoutMs: Long = 8_000,
        queryTimeoutMs: Long = 6_000,
        maxAttempts: Int = 3,
    ): List<NostrEvent> {
        relayPool.autoApproveRelayAuth(url)
        relayPool.pinEphemeral(url)
        try {
            repeat(maxAttempts) {
                val gen = relayPool.reconnectGeneration
                val sub = "authread-${seq.incrementAndGet()}"
                val events = ArrayList<NostrEvent>()
                var outcome: Outcome? = null
                try {
                    coroutineScope {
                        val collector = launch {
                            relayPool.relayEvents.collect { if (it.subscriptionId == sub) events.add(it.event) }
                        }
                        relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(sub, filter))
                        outcome = withTimeoutOrNull(queryTimeoutMs) {
                            merge(
                                relayPool.eoseSignals.filter { it == sub }.map { Outcome.EOSE },
                                relayPool.closedSignals
                                    .filter { it.first == sub && isAuthRequired(it.second) }
                                    .map { Outcome.AUTH_CLOSED },
                            ).first()
                        }
                        collector.cancelAndJoin()
                    }
                } finally {
                    relayPool.closeOnAllRelays(sub)
                }

                when (outcome) {
                    Outcome.EOSE -> return events
                    Outcome.AUTH_CLOSED -> {
                        // Wait for the (async) auto-AUTH on the live socket, then retry.
                        withTimeoutOrNull(authTimeoutMs) {
                            while (!relayPool.isAuthenticated(url)) delay(POLL_MS)
                        }
                    }
                    null -> {
                        // Genuine timeout vs a reconnect that raced the query.
                        if (relayPool.reconnectGeneration == gen) return events
                        // else: socket reconnected mid-query → retry on the fresh one.
                    }
                }
            }
            return emptyList() // exhausted retries (kept auth-closing / reconnecting)
        } finally {
            relayPool.unpinEphemeral(url)
        }
    }

    private fun isAuthRequired(message: String): Boolean =
        message.contains("auth-required", ignoreCase = true) ||
            message.contains("restricted", ignoreCase = true)

    private enum class Outcome { EOSE, AUTH_CLOSED }

    companion object {
        private const val POLL_MS = 100L
    }
}
