package cooking.zap.app.lazarus

import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip65
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** What a relay sent in answer to one request, and how the request ended. The events are unvalidated. */
data class LazarusRelayAnswer(val events: List<NostrEvent>, val outcome: LazarusRelayOutcome)

/** One relay's answer to a published event: accepted only on a NIP-01 OK true. */
data class LazarusPublishAnswer(val accepted: Boolean, val message: String? = null)

/**
 * The socket half of Lazarus, injected so the orchestration in
 * [LazarusScanEngine] runs against a fake in the JVM tests. Every query ends
 * in exactly one [LazarusRelayOutcome]; a relay that failed or timed out is
 * never reported as having answered with nothing. [LazarusSocketTransport]
 * is the app's implementation.
 */
interface LazarusRelayTransport {
    suspend fun query(url: String, filter: Filter, timeoutMs: Long): LazarusRelayAnswer
    suspend fun publish(url: String, event: NostrEvent, timeoutMs: Long): LazarusPublishAnswer

    /** Release every connection. The recovery screen calls this when it closes. */
    fun close()
}

/** The user's relay list as the app's own copy holds it (NIP-65: unmarked relays are both). */
data class LazarusUserRelays(val read: List<String>, val write: List<String>)

/** The relays a scan, the re-read and a publish work from, and how the user's relay list was found. */
data class LazarusRelayPlan(
    val read: List<String>,
    /** The user's write relays; the defaults when the list is missing, none when it's unknown. */
    val write: List<String>,
    val status: LazarusRelayListStatus
)

/** Every write relay's answer to the re-read before a restore. */
data class LazarusCurrentRead(val plan: LazarusRelayPlan, val answers: List<LazarusReadAnswer>)

/** A write relay that didn't accept the recovery, with the relay's message when it sent one. */
data class LazarusRelayRejection(val relayUrl: String, val message: String?)

/**
 * What a publish did, relay by relay. Success is judged on the write relays
 * alone (spec "Recover"): the recovery succeeded when at least one of them
 * accepted it with OK true, whatever the best-effort relays did.
 */
data class LazarusPublishReport(
    val event: NostrEvent,
    /** The write relays success was judged on. */
    val writeRelays: List<String>,
    /** Write relays that accepted the event — the only ones the UI may claim. */
    val accepted: List<String>,
    /** Write relays that rejected it, failed, or never answered. */
    val notAccepted: List<LazarusRelayRejection>,
    /** Other relays that returned versions in the scan: sent the recovery as a best effort, uncounted. */
    val bestEffort: List<String>,
    /** True when a relay list restore was judged on the write relays the restored version names. */
    val judgedOnRestoredList: Boolean,
    /** True when the write relays were the app's defaults standing in for a missing relay list. */
    val writeRelaysAreDefaults: Boolean
) {
    val succeeded: Boolean get() = accepted.isNotEmpty()
}

/**
 * The relay orchestration of Lazarus over an injected [LazarusRelayTransport]:
 * the scan plan (the user's relay list — found, missing or unknown — plus the
 * app's own relays and the default and archival sets), per-relay outcomes,
 * validation of everything a relay returns, paging, the retry of relays that
 * failed or timed out, the re-read of every write relay before signing, and
 * publishing judged on the write relays.
 *
 * Scan traffic uses the engine's own connections rather than the app's live
 * pool, whose dedup would erase the found-on record; every connection is the
 * transport's, and [close] releases all of them when the screen leaves.
 */
class LazarusScanEngine(
    private val transport: LazarusRelayTransport,
    /** The app's own copy of a user's relay list (kind 10002), or null when it holds none. */
    private val appRelayList: (pubkey: String) -> LazarusUserRelays?,
    /**
     * The relays the app itself reads and publishes the user's lists on (its
     * configured relay set), scanned too, so a version this app wrote is found.
     */
    private val appRelays: () -> List<String> = { emptyList() },
    /** The configurable default set: scanned, and the stand-in write relays for a missing relay list. */
    defaultRelays: List<String> = RelayConfig.DEFAULTS.map { it.url },
    /** The configurable archival set, which keeps superseded versions. */
    archivalRelays: List<String> = Lazarus.ARCHIVAL_RELAYS,
    /** Signature check; injected so the JVM tests run without the secp256k1 native library. */
    private val verify: (NostrEvent) -> Boolean = { it.verifySignature() },
    /** Bound on concurrent relay requests (spec "Connection budget"). */
    maxConcurrent: Int = 16,
    /** Where best-effort copies of a recovery are sent from, so they never hold up the result. */
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : LazarusRelaySource {

    private val defaultRelays = distinctRelays(defaultRelays)
    private val archivalRelays = distinctRelays(archivalRelays)
    private val permits = Semaphore(maxConcurrent)

    /** The configurable default set: the stand-in write relays for a missing relay list. */
    fun standInDefaults(): List<String> = defaultRelays

    /**
     * Relay lists this engine looked up itself (the app held no copy), per
     * account. A list that couldn't be fetched isn't kept, so the next scan
     * asks again.
     */
    private val lookedUp = ConcurrentHashMap<String, LazarusRelayPlan>()

    /** Tear down every connection and best-effort send. Call when the recovery UI closes. */
    fun close() {
        backgroundScope.cancel()
        transport.close()
    }

    /**
     * The user's relay list (spec "Relay outcomes" → Relay list): the app's
     * own copy when it has one, otherwise the newest valid kind 10002 on the
     * app's, default and archival relays. A list naming no write relays
     * counts as missing, and so does one no answering relay had: the defaults
     * stand in as write relays. When no relay answered the lookup the list is
     * unknown, and the defaults are never substituted: there are no write
     * relays, so current stays unconfirmed.
     */
    suspend fun relayPlan(pubkey: String): LazarusRelayPlan {
        appRelayList(pubkey)?.let { own ->
            val read = distinctRelays(own.read)
            val write = distinctRelays(own.write)
            if (read.isNotEmpty() || write.isNotEmpty()) return planOf(read, write)
        }
        lookedUp[pubkey]?.let { return it }
        val lookup = distinctRelays(appRelays() + defaultRelays + archivalRelays)
        val answers = queryAll(lookup, Lazarus.SCAN_TIMEOUT_MS) {
            Filter(kinds = listOf(RELAY_LIST_KIND), authors = listOf(pubkey), limit = 1)
        }
        val newest = answers.values
            .flatMap { it.events }
            .filter { isLazarusVersion(it, RELAY_LIST_KIND, pubkey, verify) }
            .maxByOrNull { it.created_at }
        val plan = when {
            newest != null -> {
                val relays = Nip65.parseRelayList(newest)
                planOf(
                    read = distinctRelays(relays.filter { it.read }.map { it.url }),
                    write = distinctRelays(relays.filter { it.write }.map { it.url })
                )
            }
            answers.values.none { it.outcome == LazarusRelayOutcome.ANSWERED } ->
                return LazarusRelayPlan(emptyList(), emptyList(), LazarusRelayListStatus.UNKNOWN)
            else -> LazarusRelayPlan(emptyList(), defaultRelays, LazarusRelayListStatus.MISSING)
        }
        lookedUp[pubkey] = plan
        return plan
    }

    /** Forget a looked-up relay list, e.g. after restoring one. */
    fun forgetRelayList(pubkey: String) {
        lookedUp.remove(pubkey)
    }

    private fun planOf(read: List<String>, write: List<String>): LazarusRelayPlan =
        if (write.isEmpty()) LazarusRelayPlan(read, defaultRelays, LazarusRelayListStatus.MISSING)
        else LazarusRelayPlan(read, write, LazarusRelayListStatus.FOUND)

    /**
     * Relays to scan: every relay in the user's list (read and write, not only
     * the first few outbox relays), the app's own relays, the defaults, and
     * the archival set.
     */
    fun scanRelays(plan: LazarusRelayPlan): List<String> =
        distinctRelays(plan.write + plan.read + appRelays() + defaultRelays + archivalRelays)

    override suspend fun fetchVersions(
        kind: Int,
        pubkey: String,
        cursors: Map<String, Long>?
    ): LazarusFetchPage {
        if (cursors != null) return fetchPage(kind, pubkey, cursors.keys.toList(), cursors)
        val plan = relayPlan(pubkey)
        val page = fetchPage(kind, pubkey, scanRelays(plan), null)
        return page.copy(
            // Current is confirmed only once one of the user's write relays answered
            currentConfirmed = plan.write.any { page.outcomes?.get(it) == LazarusRelayOutcome.ANSWERED },
            relayList = plan.status,
            writeRelays = plan.write
        )
    }

    /**
     * Ask again just the relays that failed or timed out (spec "Relay
     * outcomes" → Retry), for [mergeLazarusRetry] to fold into the scan it
     * retried. [writeRelays] are the scan's: one of them answering now
     * confirms current.
     */
    suspend fun retryRelays(
        kind: Int,
        pubkey: String,
        urls: List<String>,
        writeRelays: List<String>
    ): LazarusFetchPage {
        val page = fetchPage(kind, pubkey, distinctRelays(urls), null)
        return page.copy(
            currentConfirmed = writeRelays.any { page.outcomes?.get(it) == LazarusRelayOutcome.ANSWERED }
        )
    }

    /**
     * One page from [urls]: the first page, or the next older one per
     * [cursors]. Only valid versions count, as candidates, toward the relays
     * that returned versions, and for paging.
     */
    private suspend fun fetchPage(
        kind: Int,
        pubkey: String,
        urls: List<String>,
        cursors: Map<String, Long>?
    ): LazarusFetchPage {
        val answers = queryAll(urls, Lazarus.SCAN_TIMEOUT_MS) { url ->
            Filter(
                kinds = listOf(kind),
                authors = listOf(pubkey),
                limit = Lazarus.SCAN_LIMIT,
                until = cursors?.get(url)
            )
        }
        val tagged = mutableListOf<LazarusTaggedEvent>()
        val responding = mutableListOf<String>()
        val olderCursors = mutableMapOf<String, Long>()
        val outcomes = LinkedHashMap<String, LazarusRelayOutcome>()
        for (url in urls) {
            val answer = answers[url] ?: continue
            outcomes[url] = answer.outcome
            val valid = answer.events
                .filter { isLazarusVersion(it, kind, pubkey, verify) }
                .distinctBy { it.id }
            if (valid.isNotEmpty()) responding.add(url)
            valid.forEach { tagged.add(LazarusTaggedEvent(it, url)) }
            val previous = cursors?.get(url)
            if (valid.size >= Lazarus.SCAN_LIMIT) {
                // A full page means the relay may hold older versions. `until` is
                // inclusive, so the next page repeats the oldest event; a cursor
                // that didn't move means the relay has nothing older to give.
                val oldest = valid.minOf { it.created_at }
                if (previous == null || oldest < previous) olderCursors[url] = oldest
            } else if (previous != null && answer.outcome != LazarusRelayOutcome.ANSWERED) {
                // A page that failed or timed out isn't the end of that relay's
                // history: keep a cursor, so paging back can ask it again.
                val oldest = valid.minOfOrNull { it.created_at }
                olderCursors[url] = if (oldest != null && oldest < previous) oldest else previous
            }
        }
        return LazarusFetchPage(
            tagged = tagged,
            queriedRelays = urls,
            respondingRelays = responding,
            olderCursors = olderCursors,
            outcomes = outcomes
        )
    }

    /**
     * The re-read before a restore: every write relay, waiting for each up to
     * the timeout rather than stopping at the first answer, with each relay's
     * valid versions and whether it answered. [checkLazarusCurrent] decides.
     */
    suspend fun readCurrent(kind: Int, pubkey: String): LazarusCurrentRead {
        val plan = relayPlan(pubkey)
        val answers = queryAll(plan.write, Lazarus.REREAD_TIMEOUT_MS) {
            Filter(kinds = listOf(kind), authors = listOf(pubkey), limit = 1)
        }
        return LazarusCurrentRead(
            plan = plan,
            answers = plan.write.map { url ->
                val answer = answers[url]
                LazarusReadAnswer(
                    relayUrl = url,
                    events = answer?.events.orEmpty().filter { isLazarusVersion(it, kind, pubkey, verify) },
                    answered = answer?.outcome == LazarusRelayOutcome.ANSWERED
                )
            }
        )
    }

    /**
     * Publish one signed recovery. Success is judged on the user's write
     * relays: only those that answered OK true count. A relay list restore
     * (kind 10002) replaces the write relays themselves, so it's judged on
     * the write relays the restored version names (the current ones may be
     * the dead relays the restore is meant to fix; the defaults when it names
     * none), and the current ones still get it as a best effort. The other
     * relays that returned versions in the scan hold older copies and keep
     * serving the clobbered one otherwise, so once a write relay accepted the
     * recovery they get it too, as a best effort sent in the background: it
     * never affects or delays the result.
     */
    suspend fun publish(
        event: NostrEvent,
        pubkey: String,
        respondingRelays: List<String>
    ): LazarusPublishReport {
        val plan = relayPlan(pubkey)
        val restoringRelayList = event.kind == RELAY_LIST_KIND
        val restoredWrite = if (restoringRelayList) {
            distinctRelays(Nip65.parseRelayList(event).filter { it.write }.map { it.url })
        } else emptyList()
        val write = when {
            restoredWrite.isNotEmpty() -> restoredWrite
            restoringRelayList -> defaultRelays
            else -> plan.write
        }
        val answers = coroutineScope {
            write.map { url ->
                async {
                    url to permits.withPermit {
                        runCatching { transport.publish(url, event, Lazarus.PUBLISH_TIMEOUT_MS) }
                            .getOrElse { LazarusPublishAnswer(accepted = false, message = it.message) }
                    }
                }
            }.awaitAll()
        }
        val accepted = answers.filter { it.second.accepted }.map { it.first }
        // Best effort, after success is judged and only once it succeeded
        val extra = if (accepted.isEmpty()) emptyList()
        else distinctRelays(plan.write + respondingRelays).filter { it !in write }
        if (extra.isNotEmpty()) {
            backgroundScope.launch {
                extra.map { url ->
                    async { runCatching { permits.withPermit { transport.publish(url, event, Lazarus.PUBLISH_TIMEOUT_MS) } } }
                }.awaitAll()
            }
        }
        if (restoringRelayList) forgetRelayList(pubkey)
        return LazarusPublishReport(
            event = event,
            writeRelays = write,
            accepted = accepted,
            notAccepted = answers.filter { !it.second.accepted }.map { LazarusRelayRejection(it.first, it.second.message) },
            bestEffort = extra,
            judgedOnRestoredList = restoringRelayList,
            writeRelaysAreDefaults = if (restoringRelayList) restoredWrite.isEmpty()
            else plan.status == LazarusRelayListStatus.MISSING
        )
    }

    /** Ask every relay in [urls] at once, bounded by the connection budget. A request that couldn't even start failed. */
    private suspend fun queryAll(
        urls: List<String>,
        timeoutMs: Long,
        filterFor: (String) -> Filter
    ): Map<String, LazarusRelayAnswer> = coroutineScope {
        urls.map { url ->
            async {
                url to permits.withPermit {
                    runCatching { transport.query(url, filterFor(url), timeoutMs) }
                        .getOrElse { LazarusRelayAnswer(emptyList(), LazarusRelayOutcome.FAILED) }
                }
            }
        }.awaitAll().toMap()
    }

    private companion object {
        const val RELAY_LIST_KIND = 10002
    }
}

/**
 * Canonical relay URL for dedup and outcome keys: trimmed, no trailing
 * slash, scheme and host lowercased (a path keeps its case). Empty for
 * anything that isn't a websocket URL.
 */
fun normalizeLazarusRelayUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return ""
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "wss" && scheme != "ws") return ""
    val hostEnd = trimmed.indexOf('/', schemeEnd + 3).let { if (it < 0) trimmed.length else it }
    if (hostEnd <= schemeEnd + 3) return ""
    return scheme + "://" + trimmed.substring(schemeEnd + 3, hostEnd).lowercase() + trimmed.substring(hostEnd)
}

private fun distinctRelays(urls: List<String>): List<String> =
    urls.map(::normalizeLazarusRelayUrl).filter { it.isNotEmpty() }.distinct()
