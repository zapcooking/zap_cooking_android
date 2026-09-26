package cooking.zap.app.lazarus

import android.util.Log
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RelayMessage
import cooking.zap.app.relay.Relay
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.nostr.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The relay I/O half of Lazarus: standalone per-relay sockets owned by the
 * scan engine, so scan traffic never touches the app's live pool (whose
 * dedup would erase the found-on record) and every socket is accounted for
 * at teardown.
 *
 * The frontend PR's Copilot review set the safety precedent; this port
 * implements it from day one:
 *  - scan candidates are validated (author + kind + SIGNATURE) before they
 *    can become recovery candidates — a relay that ignores the authors
 *    filter can't inject a foreign event that would later be republished
 *    as the user's own signed event (upstream finding 3; the signature
 *    check goes one step further than the web's author+kind fix);
 *  - paging cursors derive from validated events only;
 *  - the pre-sign re-read is tri-state: a write-relay set that didn't
 *    answer is [LatestRead.Unavailable], never "no current event"
 *    (upstream findings 1 and 2 — the recheck fails closed);
 *  - publish reports exactly the write relays that acknowledged the event
 *    with OK true; best-effort extra relays are tracked separately
 *    (upstream finding 5);
 *  - every socket belongs to the engine's scope; [close] disconnects all
 *    of them, so closing the recovery screen leaves nothing behind
 *    (upstream finding 7).
 */
class LazarusScanEngine(
    /** Used only to satisfy relay AUTH challenges when required. */
    private val signer: NostrSigner? = null
) : LazarusRelaySource {

    private var scope: CoroutineScope? = null
    private val sockets = mutableListOf<Relay>()

    companion object {
        private const val TAG = "LazarusScan"
        private const val SCAN_TIMEOUT_MS = 6_000L
        private const val LATEST_TIMEOUT_MS = 4_000L
        private const val PUBLISH_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
    }

    /** Tear down every socket. Call when the recovery UI closes. */
    @Synchronized
    fun close() {
        scope?.cancel()
        scope = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.disconnect() } }
            sockets.clear()
        }
    }

    private fun engineScope(): CoroutineScope {
        scope?.let { return it }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        return s
    }

    /** The scan relay set: the user's relay list (read and write — history
     *  was observed on read-only listings), the app's configured relays,
     *  and the archival set that keeps superseded versions. */
    fun scanRelays(userRead: List<String>, userWrite: List<String>, appRelays: List<String>): List<String> =
        (userWrite + userRead + appRelays + Lazarus.ARCHIVAL_RELAYS)
            .map(::normalizeRelayUrl)
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * One relay's answer to one REQ. The socket is closed as soon as the
     * relay finishes or times out, so a slow relay doesn't stay subscribed
     * after the scan moves on; a timeout counts as no answer.
     */
    private suspend fun fetchFromRelay(
        url: String,
        filter: Filter,
        timeoutMs: Long,
        validate: (NostrEvent) -> Boolean
    ): Pair<List<NostrEvent>, Boolean> {
        val subId = "laz-${System.currentTimeMillis()}-${(0..9999).random()}"
        val relay = Relay(RelayConfig(url), Relay.createClient(), engineScope())
        synchronized(sockets) { sockets.add(relay) }
        val events = mutableListOf<NostrEvent>()
        val answered = AtomicBoolean(false)
        val done = Channel<Unit>(Channel.CONFLATED)

        val collector = engineScope().launch {
            relay.messages.collect { msg ->
                when (msg) {
                    is RelayMessage.EventMsg -> if (msg.subscriptionId == subId) {
                        // Validate before the event can influence anything.
                        if (validate(msg.event)) events.add(msg.event)
                    }
                    is RelayMessage.Eose -> if (msg.subscriptionId == subId) {
                        answered.set(true)
                        done.trySend(Unit)
                    }
                    is RelayMessage.Closed -> if (msg.subscriptionId == subId) done.trySend(Unit)
                    is RelayMessage.Auth -> onRelayAuth(relay, msg)
                    else -> {}
                }
            }
        }
        try {
            relay.connect()
            // Sends queue until the socket is up (Relay buffers pending
            // messages), so ordering collector-first then connect avoids
            // losing fast EVENT frames.
            relay.send(ClientMessage.req(subId, filter))
            withTimeoutOrNull(timeoutMs) { done.receive() }
        } catch (e: Exception) {
            Log.d(TAG, "fetch from $url failed: ${e.message}")
        } finally {
            relay.send(ClientMessage.close(subId))
            collector.cancel()
            relay.disconnect()
            synchronized(sockets) { sockets.remove(relay) }
        }
        // Validated events only; a relay whose answer was all invalid counts
        // as answered (it exists) but contributes nothing.
        return events to answered.get()
    }

    private fun onRelayAuth(relay: Relay, msg: RelayMessage.Auth) {
        // Best-effort AUTH so auth-required archival relays can answer.
        // Without a signer the relay simply stays unanswered.
        val signer = signer ?: return
        engineScope().launch {
            try {
                val authEvent = signer.signEvent(
                    kind = 22242,
                    content = "",
                    tags = listOf(
                        listOf("relay", relay.config.url),
                        listOf("challenge", msg.challenge)
                    )
                )
                relay.send(ClientMessage.event(authEvent))
            } catch (e: Exception) {
                Log.d(TAG, "relay auth failed for ${relay.config.url}: ${e.message}")
            }
        }
    }

    /** The candidate validator: author, kind, and a real signature. */
    private fun validator(expectedKind: Int, pubkey: String): (NostrEvent) -> Boolean = { event ->
        event.pubkey == pubkey && event.kind == expectedKind && runCatching { event.verifySignature() }
            .getOrDefault(false)
    }

    /** The relay set for first pages; set by the caller before scanning. */
    @Volatile
    var scanRelayUrls: List<String> = emptyList()

    override suspend fun fetchVersions(
        kind: Int,
        pubkey: String,
        cursors: Map<String, Long>?
    ): LazarusFetchPage = fetchPage(
        kind,
        pubkey,
        relayUrls = if (cursors == null) scanRelayUrls else cursors.keys.toList(),
        cursors = cursors
    )

    /** Full-page scan across [relayUrls], or the next older page per [cursors]. */
    suspend fun fetchPage(
        kind: Int,
        pubkey: String,
        relayUrls: List<String>,
        cursors: Map<String, Long>? = null
    ): LazarusFetchPage {
        val validate = validator(kind, pubkey)
        val results = relayUrls.map { url ->
            engineScope().async {
                val filter = if (cursors != null && cursors[url] != null) {
                    Filter(kinds = listOf(kind), authors = listOf(pubkey), limit = Lazarus.SCAN_LIMIT, until = cursors[url])
                } else {
                    Filter(kinds = listOf(kind), authors = listOf(pubkey), limit = Lazarus.SCAN_LIMIT)
                }
                url to fetchFromRelay(url, filter, SCAN_TIMEOUT_MS, validate)
            }
        }.map { it.await() }

        val tagged = mutableListOf<LazarusTaggedEvent>()
        val responding = mutableListOf<String>()
        val olderCursors = mutableMapOf<String, Long>()
        for ((url, pair) in results) {
            val (relayEvents, answered) = pair
            if (relayEvents.isNotEmpty()) responding.add(url)
            for (event in relayEvents) {
                tagged.add(LazarusTaggedEvent(event, url))
            }
            // A full VALIDATED page means the relay may hold older versions.
            // `until` is inclusive, so the next page repeats the oldest event;
            // a cursor that didn't move means nothing older is coming.
            if (relayEvents.size >= Lazarus.SCAN_LIMIT) {
                val oldest = relayEvents.minOf { it.created_at }
                if (cursors?.get(url) == null || oldest < cursors[url]!!) olderCursors[url] = oldest
            } else if (answered && cursors?.get(url) != null) {
                // Page drained: relay answered but has nothing older.
            }
        }
        return LazarusFetchPage(
            tagged = tagged,
            queriedRelays = relayUrls,
            respondingRelays = responding,
            olderCursors = olderCursors
        )
    }

    /**
     * The newest version on the user's write relays right now, to catch edits
     * made after a scan (another device or client) before a restore
     * overwrites them. TRI-STATE by the upstream precedent: a write-relay set
     * that didn't answer is [LatestRead.Unavailable] — the caller must fail
     * closed, never treat it as "no current event".
     */
    suspend fun fetchLatestVersion(kind: Int, pubkey: String, writeRelays: List<String>): LatestRead {
        if (writeRelays.isEmpty()) return LatestRead.Unavailable
        val validate = validator(kind, pubkey)
        val results = writeRelays.map { url ->
            engineScope().async {
                url to fetchFromRelay(
                    url,
                    Filter(kinds = listOf(kind), authors = listOf(pubkey), limit = 1),
                    LATEST_TIMEOUT_MS,
                    validate
                )
            }
        }.map { it.await() }
        var newest: NostrEvent? = null
        var anyAnswered = false
        for ((_, pair) in results) {
            val (events, answered) = pair
            if (answered) anyAnswered = true
            for (event in events) {
                if (newest == null || event.created_at > newest.created_at) newest = event
            }
        }
        return when {
            !anyAnswered -> LatestRead.Unavailable
            newest != null -> LatestRead.Found(newest)
            else -> LatestRead.Empty
        }
    }

    /**
     * Publish one signed event. Success is judged ONLY on relays that
     * acknowledged with OK true — the returned lists are exactly what the
     * UI may claim. [extraRelays] (relays that answered the scan but aren't
     * write relays) get the event best-effort and are reported separately.
     */
    suspend fun publishEvent(
        event: NostrEvent,
        writeRelays: List<String>,
        extraRelays: List<String>
    ): LazarusPublishAck {
        val msg = ClientMessage.event(event)
        val accepted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        suspend fun sendTo(urls: List<String>, track: (String, Boolean) -> Unit) {
            val jobs = urls.map { url ->
                engineScope().launch {
                    val relay = Relay(RelayConfig(url), Relay.createClient(), engineScope())
                    synchronized(sockets) { sockets.add(relay) }
                    val ok = AtomicBoolean(false)
                    val done = Channel<Unit>(Channel.CONFLATED)
                    val collector = engineScope().launch {
                        relay.messages.collect { m ->
                            when (m) {
                                is RelayMessage.Ok -> if (m.eventId == event.id) {
                                    ok.set(m.accepted)
                                    done.trySend(Unit)
                                }
                                is RelayMessage.Closed, is RelayMessage.Eose -> done.trySend(Unit)
                                is RelayMessage.Auth -> onRelayAuth(relay, m)
                                else -> {}
                            }
                        }
                    }
                    try {
                        relay.connect()
                        relay.send(msg)
                        withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { done.receive() }
                    } catch (_: Exception) {
                    } finally {
                        collector.cancel()
                        relay.disconnect()
                        synchronized(sockets) { sockets.remove(relay) }
                    }
                    track(url, ok.get())
                }
            }
            jobs.forEach { it.join() }
        }

        sendTo(writeRelays.distinct()) { url, ok -> if (ok) accepted.add(url) else failed.add(url) }
        val extras = extraRelays.filter { it !in writeRelays }.distinct()
        sendTo(extras) { _, _ -> /* best effort by spec */ }
        return LazarusPublishAck(acceptedWriteRelays = accepted, unconfirmedWriteRelays = failed, extraRelays = extras)
    }

    /** Per-relay publish outcome: only OK-true write relays count as published. */
    data class LazarusPublishAck(
        val acceptedWriteRelays: List<String>,
        val unconfirmedWriteRelays: List<String>,
        val extraRelays: List<String>
    )
}

/** Tri-state pre-sign re-read (upstream findings 1 and 2). */
sealed class LatestRead {
    /** Every write relay timed out or failed — the current version is UNKNOWN. */
    object Unavailable : LatestRead()

    /** Relays answered; no current version exists. */
    object Empty : LatestRead()

    data class Found(val event: NostrEvent) : LatestRead()
}

private fun normalizeRelayUrl(url: String): String = url.trim().trimEnd('/')
