package cooking.zap.app.lazarus

import cooking.zap.app.nostr.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lazarus core: scan, rank, delta, re-read check, recover-draft. Pure — no
 * sockets, no signer. Everything that opens a relay connection implements
 * [LazarusRelaySource]; the only signing surface is [buildLazarusRecoveryDraft],
 * whose output the caller signs and publishes exactly once, on an explicit
 * user click.
 *
 * Kotlin port of the spec's reference core, spec 0.6.1-draft, kept in step
 * with zap.cooking web. Profile deltas compare every metadata key and every
 * tag, not a fixed set, since a restore replaces all of them.
 *
 * Invariants (https://github.com/dmnyc/lazarus/blob/main/SPEC.md):
 *  1. This module never publishes. Recovery happens only when the UI asks
 *     the signer for exactly one event on an explicit click.
 *  2. Every candidate found is returned, including empty ones.
 *  3. Empty candidates are never recommended (except kinds flagged
 *     meaningfulEmpty, where nothing is recommended at all).
 *  4. The only signing surface is the draft builder; the caller owns the
 *     signer and the click.
 */
object Lazarus {
    /** Versions requested per relay. A relay that fills a page can be paged further back. */
    const val SCAN_LIMIT = 50

    /** Per-relay timeout for a scan request (the spec's reference value). */
    const val SCAN_TIMEOUT_MS = 6_000L

    /** Per-relay timeout for the re-read of the write relays before signing. */
    const val REREAD_TIMEOUT_MS = 4_000L

    /** Per-relay wait for the OK to a published recovery. */
    const val PUBLISH_TIMEOUT_MS = 15_000L

    /**
     * A clobber drops a large share of a list at once, while curation moves a few
     * items at a time. A step between two versions counts as a sudden drop when
     * the later one is missing at least this share of the earlier one's items,
     * and at least this many.
     */
    const val CLOBBER_MIN_LOSS_RATIO = 0.2
    const val CLOBBER_MIN_LOSS_ITEMS = 5

    /** Drops within this long of each other are one clobber episode. */
    const val CLOBBER_EPISODE_SECONDS = 24 * 60 * 60

    /**
     * A clobber the list has since been edited on this many times, over at least
     * this long, is settled: the current version is the user's choice.
     */
    const val SETTLED_MIN_EDITS = 5
    const val SETTLED_MIN_SECONDS = 7 * 24 * 60 * 60

    /**
     * Relays to scan beyond the user's own set: they have been seen holding
     * superseded versions. relay.ditto.pub keeps every version, hist.nostr.land
     * keeps recent history, and the rest are large relays that often still
     * have versions the user's own relays already replaced. Only relays that
     * actually showed history are listed, since each is a socket.
     */
    val ARCHIVAL_RELAYS = listOf(
        "wss://relay.ditto.pub",
        "wss://hist.nostr.land",
        "wss://nos.lol",
        "wss://nostr.mom",
        "wss://purplepag.es",
        "wss://nostr.bitcoiner.social"
    )
}

/**
 * How a relay request ended (spec "Relay outcomes"): the relay sent EOSE,
 * failed (couldn't connect, or closed the request or the connection first),
 * or timed out. Only an answered relay counts as having nothing: silence is
 * not evidence.
 */
enum class LazarusRelayOutcome { ANSWERED, FAILED, TIMED_OUT }

/**
 * The user's relay list: found (in the app's copy or on relays), missing
 * (relays answered without one, so the defaults stand in as write relays),
 * or unknown (no relay answered the lookup, so there are no write relays and
 * current can't be confirmed).
 */
enum class LazarusRelayListStatus { FOUND, MISSING, UNKNOWN }

/**
 * An event together with the relay it was observed on. An empty [relayUrl]
 * marks a version known only from the app's own copy.
 */
data class LazarusTaggedEvent(val event: NostrEvent, val relayUrl: String)

data class LazarusCandidate(
    val event: NostrEvent,
    /** Relay URLs where this exact event id was observed. */
    val foundOn: List<String>,
    val itemCount: LazarusItemCount,
    /** True for the most recent candidate (what the scan saw as current). */
    val isCurrent: Boolean = false,
    val isRecommended: Boolean = false
)

data class LazarusScanResult(
    val kind: Int,
    /** Ranked candidates. Meaningful-empty kinds: recency order, nothing recommended. */
    val candidates: List<LazarusCandidate>,
    val current: LazarusCandidate?,
    val recommended: LazarusCandidate?,
    /** True for meaningful-empty kinds: the user must choose with intent. */
    val requiresIntentConfirmation: Boolean,
    val queriedRelays: List<String>,
    /** Relays that returned at least one valid version. */
    val respondingRelays: List<String>,
    /**
     * True once at least one of the user's write relays answered. Until then
     * the newest version found may not be current, and nothing is recommended.
     */
    val currentConfirmed: Boolean = true,
    /** How each queried relay's request ended, when the source reports it. */
    val relayOutcomes: Map<String, LazarusRelayOutcome>? = null,
    val relayList: LazarusRelayListStatus? = null,
    /** The write relays current was confirmed against (the defaults when the list is missing). */
    val writeRelays: List<String> = emptyList(),
    /**
     * Relays that may hold versions older than the scan returned, keyed to the
     * created_at to page back from. Empty when the scan saw everything.
     */
    val olderCursors: Map<String, Long> = emptyMap()
)

/** Decrypted private items (NIP-51), keyed by event id. */
typealias LazarusPrivateTags = Map<String, List<List<String>>>

/** The relays' answer to a page of the scan. */
data class LazarusFetchPage(
    val tagged: List<LazarusTaggedEvent>,
    val queriedRelays: List<String>,
    val respondingRelays: List<String>,
    /** Relays whose answer filled the page, keyed to the cursor for the next one. */
    val olderCursors: Map<String, Long> = emptyMap(),
    /** How each relay's request ended. A source that doesn't report it leaves this null. */
    val outcomes: Map<String, LazarusRelayOutcome>? = null,
    /**
     * Whether one of the user's write relays answered. A source that doesn't
     * report it (null) is taken as confirmed.
     */
    val currentConfirmed: Boolean? = null,
    val relayList: LazarusRelayListStatus? = null,
    val writeRelays: List<String> = emptyList()
)

/**
 * The relay I/O half. Implementations open the sockets; everything here
 * stays pure and testable with a fake source.
 */
interface LazarusRelaySource {
    /**
     * Fetch versions from the scan relays. With [cursors], fetch the next
     * older page from just those relays instead.
     */
    suspend fun fetchVersions(
        kind: Int,
        pubkey: String,
        cursors: Map<String, Long>? = null
    ): LazarusFetchPage
}

/** A scan no relay answered: an error with a retry, never "no versions found".
 *  Carries what the scan did learn, so the UI can show which relays failed. */
class LazarusScanFailedException(
    val queriedRelays: List<String> = emptyList(),
    val outcomes: Map<String, LazarusRelayOutcome>? = null
) : Exception("No relay answered the scan")

/**
 * Whether an event a relay returned counts as a version of the scanned list.
 * Relays are untrusted: one can return events outside the filter, or forged
 * ones, and a restore would sign their content as the user's own. Nothing
 * may become a candidate, count toward found-on, or move a paging cursor
 * before passing this check. [verify] checks the signature (injected so the
 * JVM tests can run without the secp256k1 native library).
 */
fun isLazarusVersion(
    event: NostrEvent,
    kind: Int,
    pubkey: String,
    verify: (NostrEvent) -> Boolean
): Boolean = event.kind == kind && event.pubkey == pubkey && runCatching { verify(event) }.getOrDefault(false)

/** A candidate's total item count as a range: exact once its private items
 *  are decrypted (or when it has none), estimated from the encrypted size
 *  otherwise. */
fun getLazarusItemRange(itemCount: LazarusItemCount): LazarusCountRange {
    val privateCount = itemCount.privateCount
    if (privateCount != null) return LazarusCountRange(itemCount.count + privateCount, itemCount.count + privateCount)
    val estimate = itemCount.privateEstimate
    if (estimate != null) return LazarusCountRange(itemCount.count + estimate.min, itemCount.count + estimate.max)
    return LazarusCountRange(itemCount.count, itemCount.count)
}

/** False when encrypted private items could be neither decrypted nor sized. */
fun isLazarusSizeKnown(itemCount: LazarusItemCount): Boolean =
    !itemCount.partial || itemCount.privateEstimate != null

private fun countItems(
    profile: LazarusKindProfile,
    tags: List<List<String>>,
    content: String,
    privateTags: List<List<String>>?
): LazarusItemCount {
    val itemCount = profile.itemCount(tags, content)
    val types = profile.privateItemTypes ?: return itemCount
    privateTags ?: return itemCount
    return LazarusItemCount(
        count = itemCount.count,
        partial = false,
        privateCount = countItemTags(privateTags, types)
    )
}

suspend fun scanLazarusKind(
    kind: Int,
    pubkey: String,
    source: LazarusRelaySource
): LazarusScanResult {
    val profile = getLazarusKindProfile(kind)
        ?: throw IllegalArgumentException("kind $kind is not in the Lazarus registry")
    val page = source.fetchVersions(kind, pubkey)
    // A scan no relay answered failed: it isn't an empty result. Versions that
    // arrived before the relays failed are still shown (invariant 2).
    val outcomes = page.outcomes
    if (outcomes != null && page.tagged.isEmpty() && LazarusRelayOutcome.ANSWERED !in outcomes.values) {
        throw LazarusScanFailedException(page.queriedRelays, outcomes)
    }
    return rankLazarusCandidates(
        profile,
        page.tagged,
        page.queriedRelays,
        page.respondingRelays,
        currentConfirmed = page.currentConfirmed ?: true
    ).copy(
        olderCursors = page.olderCursors,
        relayOutcomes = outcomes,
        relayList = page.relayList,
        writeRelays = page.writeRelays
    )
}

/** True when the scan got versions but no relay answered: what arrived may be incomplete. */
fun lazarusScanReachedNoRelay(scan: LazarusScanResult): Boolean {
    val outcomes = scan.relayOutcomes ?: return false
    return LazarusRelayOutcome.ANSWERED !in outcomes.values
}

/** Relays whose request failed or timed out, in query order: the ones a retry asks again. */
fun lazarusUnansweredRelays(scan: LazarusScanResult): List<String> {
    val outcomes = scan.relayOutcomes ?: return emptyList()
    return scan.queriedRelays.filter { url -> outcomes[url].let { it != null && it != LazarusRelayOutcome.ANSWERED } }
}

/**
 * Fetch the next page of older versions from relays whose last answer filled
 * the scan limit, and re-rank with them merged in.
 */
suspend fun loadOlderLazarusVersions(
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    pubkey: String,
    source: LazarusRelaySource,
    privateTags: LazarusPrivateTags = emptyMap()
): LazarusScanResult {
    val cursors = scan.olderCursors
    if (cursors.isEmpty()) return scan
    val older = source.fetchVersions(profile.kind, pubkey, cursors)
    return mergeLazarusOlderPage(profile, scan, older, privateTags)
}

/** Merge a page of older versions into the scan it was paged from, with the page's cursors. */
fun mergeLazarusOlderPage(
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    older: LazarusFetchPage,
    privateTags: LazarusPrivateTags = emptyMap()
): LazarusScanResult = rankLazarusCandidates(
    profile,
    scanToTagged(scan) + older.tagged,
    scan.queriedRelays,
    (scan.respondingRelays + older.respondingRelays).distinct(),
    privateTags,
    scan.currentConfirmed
).copy(
    olderCursors = older.olderCursors,
    relayOutcomes = scan.relayOutcomes,
    relayList = scan.relayList,
    writeRelays = scan.writeRelays
)

/**
 * Merge a retry of the relays that failed or timed out (spec "Relay
 * outcomes" → Retry) into the scan it retried, without repeating the rest of
 * the scan: their fresh outcomes replace the old ones, what they returned
 * joins the candidates, and a write relay answering now confirms current.
 */
fun mergeLazarusRetry(
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    retry: LazarusFetchPage,
    privateTags: LazarusPrivateTags = emptyMap()
): LazarusScanResult {
    val outcomes = (scan.relayOutcomes ?: emptyMap()) + (retry.outcomes ?: emptyMap())
    val confirmed = scan.currentConfirmed || retry.currentConfirmed == true
    return rankLazarusCandidates(
        profile,
        scanToTagged(scan) + retry.tagged,
        (scan.queriedRelays + retry.queriedRelays).distinct(),
        (scan.respondingRelays + retry.respondingRelays).distinct(),
        privateTags,
        confirmed
    ).copy(
        olderCursors = scan.olderCursors + retry.olderCursors,
        relayOutcomes = outcomes,
        relayList = scan.relayList,
        writeRelays = scan.writeRelays
    )
}

/**
 * Add a version the re-read before a restore found (spec "Recover": a newer
 * version becomes current) to the scan, so the list, the recommendation and
 * the next delta all measure against it. [foundOn] may be empty for a
 * version known only from the app's own copy. [confirmed] marks a re-read
 * that a write relay answered.
 */
fun withLazarusVersion(
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    event: NostrEvent,
    foundOn: List<String>,
    privateTags: LazarusPrivateTags = emptyMap(),
    confirmed: Boolean = false
): LazarusScanResult {
    val tagged = if (foundOn.isEmpty()) listOf(LazarusTaggedEvent(event, ""))
    else foundOn.map { LazarusTaggedEvent(event, it) }
    return rankLazarusCandidates(
        profile,
        scanToTagged(scan) + tagged,
        scan.queriedRelays,
        (scan.respondingRelays + foundOn).distinct(),
        privateTags,
        scan.currentConfirmed || confirmed
    ).copy(
        olderCursors = scan.olderCursors,
        relayOutcomes = scan.relayOutcomes,
        relayList = scan.relayList,
        writeRelays = scan.writeRelays
    )
}

private fun scanToTagged(scan: LazarusScanResult): List<LazarusTaggedEvent> =
    scan.candidates.flatMap { candidate ->
        if (candidate.foundOn.isEmpty()) listOf(LazarusTaggedEvent(candidate.event, ""))
        else candidate.foundOn.map { LazarusTaggedEvent(candidate.event, it) }
    }

/** Whether a list of `laterMax` items looks clobbered next to an earlier one of `earlierMin`. */
internal fun looksClobbered(laterMax: Int, earlierMin: Int): Boolean {
    if (earlierMin <= 0) return false
    if (laterMax <= 0) return true
    val loss = earlierMin - laterMax
    return loss >= Lazarus.CLOBBER_MIN_LOSS_ITEMS && loss >= earlierMin * Lazarus.CLOBBER_MIN_LOSS_RATIO
}

/** Versions with a known size, oldest first. */
internal fun knownTimeline(candidates: List<LazarusCandidate>): List<LazarusCandidate> =
    candidates
        .filter { isLazarusSizeKnown(it.itemCount) }
        .sortedWith(compareBy({ it.event.created_at }, { it.event.id }))

internal data class ClobberEpisode(
    /** Timeline indexes of each version that dropped suddenly from the one before it */
    val drops: List<Int>,
    /** Indexes of the episode's ends: just before its first drop, just after its last */
    val first: Int,
    val last: Int
)

/**
 * Sudden drops in a timeline, newest episode first. Drops back to back or
 * within a day of each other are one episode, however the list bounced.
 */
internal fun findClobberEpisodes(timeline: List<LazarusCandidate>): List<ClobberEpisode> {
    fun range(i: Int) = getLazarusItemRange(timeline[i].itemCount)
    val episodes = mutableListOf<ClobberEpisode>()
    for (i in 1 until timeline.size) {
        if (!looksClobbered(range(i).max, range(i - 1).min)) continue
        val open = episodes.lastOrNull()
        if (open != null && (i - 1 == open.last ||
                timeline[i].event.created_at - timeline[open.last].event.created_at <=
                Lazarus.CLOBBER_EPISODE_SECONDS)
        ) {
            episodes[episodes.lastIndex] = open.copy(drops = open.drops + i, last = i)
        } else {
            episodes.add(ClobberEpisode(drops = listOf(i), first = i - 1, last = i))
        }
    }
    return episodes.reversed()
}

/**
 * The version to recommend: the fullest version from just before a drop in
 * the most recent clobber episode the current version still hasn't recovered
 * from. Curation moves a few items at a time and never registers as a drop,
 * so a list that shrank slowly keeps its current version, however far it
 * shrank. A clobber the list has since been edited on several times over at
 * least a week is settled, so the current version is the user's choice.
 * Restore points are never empty (invariant 3), and estimated sizes are
 * compared conservatively.
 */
internal fun findRestorePoint(
    candidates: List<LazarusCandidate>,
    current: LazarusCandidate
): LazarusCandidate? {
    val timeline = knownTimeline(candidates)
    val minOf = { c: LazarusCandidate -> getLazarusItemRange(c.itemCount).min }
    val currentMax = getLazarusItemRange(current.itemCount).max

    for (episode in findClobberEpisodes(timeline)) {
        val restorePoint = episode.drops
            .map { timeline[it - 1] }
            .reduce { fullest, c -> if (minOf(c) >= minOf(fullest)) c else fullest }
        if (!looksClobbered(currentMax, minOf(restorePoint))) continue
        val edits = timeline.size - 1 - episode.last
        val settledFor = current.event.created_at - timeline[episode.last].event.created_at
        if (edits >= Lazarus.SETTLED_MIN_EDITS && settledFor >= Lazarus.SETTLED_MIN_SECONDS) return null
        return restorePoint
    }
    return null
}

fun rankLazarusCandidates(
    profile: LazarusKindProfile,
    taggedEvents: List<LazarusTaggedEvent>,
    queriedRelays: List<String> = emptyList(),
    respondingRelays: List<String> = emptyList(),
    privateTags: LazarusPrivateTags = emptyMap(),
    currentConfirmed: Boolean = true
): LazarusScanResult {
    val byId = LinkedHashMap<String, LazarusCandidate>()
    for ((event, relayUrl) in taggedEvents) {
        val existing = byId[event.id]
        if (existing != null) {
            if (relayUrl.isNotEmpty() && relayUrl !in existing.foundOn) {
                byId[event.id] = existing.copy(foundOn = existing.foundOn + relayUrl)
            }
            continue
        }
        byId[event.id] = LazarusCandidate(
            event = event,
            foundOn = if (relayUrl.isEmpty()) emptyList() else listOf(relayUrl),
            itemCount = countItems(profile, event.tags, event.content, privateTags[event.id])
        )
    }

    val candidates = byId.values.toList()
    val newestFirst = candidates.sortedWith(
        compareByDescending<LazarusCandidate> { it.event.created_at }.thenBy { it.event.id }
    )
    val current = newestFirst.firstOrNull()?.copy(isCurrent = true)

    val requiresIntentConfirmation = profile.ranking == LazarusRanking.INTENT

    var recommended: LazarusCandidate? = null
    val ordered: List<LazarusCandidate> = if (profile.ranking == LazarusRanking.COUNT) {
        // Private items count too: a private-only mute list has no public tags,
        // so ranking on tags alone would score an emptied list like a full one.
        val sized = candidates.sortedWith(
            compareByDescending<LazarusCandidate> {
                val range = getLazarusItemRange(it.itemCount); range.min + range.max
            }.thenByDescending { it.event.created_at }
        )
        // Nothing is recommended while the current size is unknown, or while no
        // write relay answered: current may be a version the user already replaced
        if (currentConfirmed && current != null && isLazarusSizeKnown(current.itemCount)) {
            recommended = findRestorePoint(candidates, current)
        }
        sized
    } else {
        // 'recency' and 'intent' kinds: recency order, no recommendation. For
        // meaningful-empty kinds ranking is forbidden by spec: the user chooses
        // with intent.
        newestFirst
    }

    val orderedWithFlags = ordered.map { candidate ->
        candidate.copy(
            isCurrent = current?.event?.id == candidate.event.id,
            isRecommended = recommended?.event?.id == candidate.event.id
        )
    }
    val currentFlagged = current?.let { c -> orderedWithFlags.first { it.event.id == c.event.id } }
    val recommendedFlagged = recommended?.let { r -> orderedWithFlags.first { it.event.id == r.event.id } }

    return LazarusScanResult(
        kind = profile.kind,
        candidates = orderedWithFlags,
        current = currentFlagged,
        recommended = recommendedFlagged,
        requiresIntentConfirmation = requiresIntentConfirmation,
        queriedRelays = queriedRelays,
        respondingRelays = respondingRelays,
        currentConfirmed = currentConfirmed
    )
}

/**
 * Re-rank a scan once private items have been decrypted. Candidates missing
 * from the map keep their size-based estimate.
 */
fun applyLazarusPrivateTags(
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    privateTags: LazarusPrivateTags
): LazarusScanResult = rankLazarusCandidates(
    profile,
    scanToTagged(scan),
    scan.queriedRelays,
    scan.respondingRelays,
    privateTags,
    scan.currentConfirmed
).copy(
    olderCursors = scan.olderCursors,
    relayOutcomes = scan.relayOutcomes,
    relayList = scan.relayList,
    writeRelays = scan.writeRelays
)

enum class LazarusSortOrder { DATE, SIZE }

/** Candidates for display: newest first, or largest first with newer versions first on ties. */
fun sortLazarusCandidates(
    candidates: List<LazarusCandidate>,
    order: LazarusSortOrder
): List<LazarusCandidate> {
    val byDate = compareByDescending<LazarusCandidate> { it.event.created_at }.thenBy { it.event.id }
    if (order == LazarusSortOrder.DATE) return candidates.sortedWith(byDate)
    return candidates.sortedWith(
        compareByDescending<LazarusCandidate> {
            val range = getLazarusItemRange(it.itemCount); range.min + range.max
        }.thenByDescending { it.event.created_at }.thenBy { it.event.id }
    )
}

/**
 * Whether a version is an empty one from the past: evidence of a clobber
 * rather than a state anyone wants back, so a list can hide it until asked
 * and never offers it for restore. Never the current version, and never on
 * meaningful-empty kinds, where an empty version is a valid option.
 */
fun isPastEmptyVersion(candidate: LazarusCandidate, profile: LazarusKindProfile): Boolean =
    !candidate.isCurrent &&
        !profile.meaningfulEmpty &&
        isLazarusSizeKnown(candidate.itemCount) &&
        getLazarusItemRange(candidate.itemCount).max == 0

sealed class LazarusListItem {
    data class Version(val candidate: LazarusCandidate) : LazarusListItem()
    data class Group(val candidates: List<LazarusCandidate>, val clobbered: Boolean) : LazarusListItem()
}

/**
 * Candidates newest first, folded into groups so a long history stays
 * readable: runs of small edits, and clobber episodes, flagged so the sudden
 * drops stand out. The current version keeps its own row, as do empty
 * versions when shown. Only countable list kinds are grouped, where most
 * versions are small edits of the same list; every version stays reachable
 * by expanding its group. Past empty versions can be left out, for lists
 * that show them on request.
 */
fun groupLazarusCandidates(
    scan: LazarusScanResult,
    profile: LazarusKindProfile,
    hidePastEmpty: Boolean = false
): List<LazarusListItem> {
    val newestFirst = sortLazarusCandidates(scan.candidates, LazarusSortOrder.DATE)
    val visible = if (hidePastEmpty) {
        newestFirst.filter { !isPastEmptyVersion(it, profile) }
    } else newestFirst
    if (profile.ranking != LazarusRanking.COUNT) {
        return visible.map { LazarusListItem.Version(it) }
    }

    val timeline = knownTimeline(scan.candidates)
    val episodeOf = mutableMapOf<String, Int>()
    findClobberEpisodes(timeline).forEachIndexed { n, episode ->
        for (i in episode.first..episode.last) episodeOf[timeline[i].event.id] = n
    }

    val items = mutableListOf<LazarusListItem>()
    val run = mutableListOf<LazarusCandidate>()
    var runEpisode: Int? = null
    fun flush() {
        when (run.size) {
            1 -> items.add(LazarusListItem.Version(run[0]))
            else -> if (run.isNotEmpty()) items.add(LazarusListItem.Group(run.toList(), runEpisode != null))
        }
        run.clear()
    }
    for (candidate in visible) {
        val empty = isLazarusSizeKnown(candidate.itemCount) &&
            getLazarusItemRange(candidate.itemCount).max == 0
        if (candidate.isCurrent || empty) {
            flush()
            items.add(LazarusListItem.Version(candidate))
            continue
        }
        val episode = episodeOf[candidate.event.id]
        if (run.isNotEmpty() && episode != runEpisode) flush()
        runEpisode = episode
        run.add(candidate)
    }
    flush()
    return items
}

data class LazarusDelta(
    val added: List<List<String>>,
    val removed: List<List<String>>,
    val addedCount: Int,
    val removedCount: Int,
    /** True when recovery would grow the list. */
    val grows: Boolean,
    /** True when recovery would shrink the list below current. */
    val shrinks: Boolean,
    /** The chosen version's encrypted private items weren't decrypted, so they aren't counted above. */
    val chosenPrivateUncounted: Boolean = false,
    /** The current version's encrypted private items weren't decrypted, so they aren't counted above. */
    val currentPrivateUncounted: Boolean = false
) {
    /**
     * True when either version has encrypted private items that weren't
     * decrypted, so the changes above cover public tags only.
     */
    val privateUnknown: Boolean get() = chosenPrivateUncounted || currentPrivateUncounted
}

/**
 * What makes two tags the same item: their type and value. A relay hint or
 * petname a client rewrote doesn't change who is followed or muted. On relay
 * lists the read/write marker counts too, since it changes what the relay is
 * for.
 */
internal fun tagIdentity(tag: List<String>, kind: Int): String {
    val width = if (kind == 10002) 3 else 2
    return tag.take(width).joinToString(
        separator = ",",
        prefix = "[",
        postfix = "]"
    ) { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
}

fun computeLazarusDelta(
    chosen: NostrEvent,
    current: NostrEvent?,
    privateTags: LazarusPrivateTags = emptyMap()
): LazarusDelta {
    // Decrypted private items are compared together with the public tags, so
    // an item that only moved between public and private isn't a change
    fun itemsOf(event: NostrEvent?): Pair<List<List<String>>, Boolean> {
        event ?: return emptyList<List<String>>() to false
        val decrypted = privateTags[event.id]
        return Pair(
            event.tags + (decrypted ?: emptyList()),
            decrypted == null && getContentEncryption(event.content) != null
        )
    }
    val identity = { tag: List<String> -> tagIdentity(tag, chosen.kind) }
    fun unique(tags: List<List<String>>): List<List<String>> =
        LinkedHashMap<String, List<String>>().apply { tags.forEach { putIfAbsent(identity(it), it) } }.values.toList()

    val (chosenTags, chosenUnknown) = itemsOf(chosen)
    val (currentTags, currentUnknown) = itemsOf(current)
    val chosenUnique = unique(chosenTags)
    val currentUnique = unique(currentTags)
    val chosenIds = chosenUnique.map(identity).toSet()
    val currentIds = currentUnique.map(identity).toSet()
    val added = chosenUnique.filter { identity(it) !in currentIds }
    val removed = currentUnique.filter { identity(it) !in chosenIds }
    return LazarusDelta(
        added = added,
        removed = removed,
        addedCount = added.size,
        removedCount = removed.size,
        grows = added.isNotEmpty() && added.size >= removed.size,
        shrinks = removed.size > added.size,
        chosenPrivateUncounted = chosenUnknown,
        currentPrivateUncounted = currentUnknown
    )
}

/** Well-known profile fields, shown first in this order. */
private val PROFILE_FIELDS = listOf(
    "name",
    "display_name",
    "about",
    "picture",
    "banner",
    "nip05",
    "lud16",
    "lud06",
    "website"
)

private val profileJson = Json { ignoreUnknownKeys = true }

/**
 * The profile (kind 0) fields and tags a restore would change. Profile
 * content is extensible (pronouns, bot, client-specific fields) and a
 * restore replaces all of it, tags included, so every field and tag counts:
 * the well-known fields first, then any other field, then tags grouped by
 * name (NIP-30 custom emoji live there, as "emoji tags"). Values that aren't
 * strings show as JSON, and an empty or blank string reads as absent.
 */
fun computeLazarusProfileChanges(
    chosen: NostrEvent,
    current: NostrEvent?
): List<LazarusProfileChange> {
    fun fieldsOf(event: NostrEvent?): Map<String, String?> = try {
        val content = event?.content?.ifEmpty { null } ?: "{}"
        val obj = profileJson.parseToJsonElement(content) as? JsonObject ?: emptyMap()
        obj.mapValues { (_, value) ->
            when {
                value is JsonNull -> null
                value is JsonPrimitive && value.isString -> value.content.takeIf { it.isNotBlank() }
                else -> value.toString()
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
    // Tag order carries no meaning here, so each tag name compares as a sorted set
    fun tagsOf(event: NostrEvent?): Map<String, String> {
        val byName = LinkedHashMap<String, MutableList<String>>()
        for (tag in event?.tags ?: emptyList()) {
            val name = tag.firstOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            byName.getOrPut("$name tags") { mutableListOf() }.add(tag.drop(1).joinToString(" "))
        }
        return byName.mapValues { (_, values) -> values.sorted().joinToString(", ") }
    }
    val to = fieldsOf(chosen)
    val from = fieldsOf(current)
    val toTags = tagsOf(chosen)
    val fromTags = tagsOf(current)
    val otherFields = (from.keys + to.keys).filter { it !in PROFILE_FIELDS }.distinct().sorted()
    val tagFields = (fromTags.keys + toTags.keys).distinct().sorted()
    return ((PROFILE_FIELDS + otherFields).map { field ->
        LazarusProfileChange(field = field, from = from[field], to = to[field])
    } + tagFields.map { field ->
        LazarusProfileChange(field = field, from = fromTags[field], to = toTags[field], isTags = true)
    }).filter { it.from != it.to }
}

data class LazarusProfileChange(
    val field: String,
    val from: String?,
    val to: String?,
    /** True for a group of tags ("emoji tags"), false for a content field. */
    val isTags: Boolean = false
)

/**
 * One write relay's answer to the re-read before a restore: the valid
 * versions it sent, and whether it answered (sent EOSE). Versions from a
 * relay that failed or timed out still show an edit.
 */
data class LazarusReadAnswer(
    val relayUrl: String,
    val events: List<NostrEvent>,
    val answered: Boolean
)

sealed class LazarusCurrentCheck {
    /** Nothing newer than the reviewed version, and a write relay answered: sign against [current]. */
    data class Proceed(val current: NostrEvent?) : LazarusCurrentCheck()

    /** A newer version appeared: it becomes current, and the delta is recomputed and asked again. */
    data class Changed(val current: NostrEvent) : LazarusCurrentCheck()

    /**
     * The app's own copy is newer than every version the re-read could read,
     * but the app keeps only a parsed copy, not the event, so no delta can be
     * computed against it. Nothing may be signed until a scan finds it.
     */
    data class ChangedLocally(val createdAt: Long) : LazarusCurrentCheck()

    /** No write relay answered: current can't be confirmed, and nothing may be signed. */
    object Unconfirmed : LazarusCurrentCheck()
}

/**
 * Decide the re-read before a restore (the spec's `checkCurrent`). The list
 * changed only if the local copy or a write relay holds a version newer than
 * the one the delta was computed against: the re-read asks fewer relays than
 * the scan, so an older copy is no edit. Otherwise at least one write relay
 * must have answered (an answer with no events counts), or current can't be
 * confirmed and the restore must not go ahead. The local copy can't confirm
 * it on its own.
 *
 * [localCreatedAt] is the created_at of the version the app's own copy was
 * built from, for stores that keep a parsed list without the event.
 */
fun checkLazarusCurrent(
    reviewed: NostrEvent?,
    local: NostrEvent?,
    answers: List<LazarusReadAnswer>,
    localCreatedAt: Long? = null
): LazarusCurrentCheck {
    var newest = reviewed
    for (event in listOfNotNull(local) + answers.flatMap { it.events }) {
        if (newest == null || event.created_at > newest.created_at) newest = event
    }
    if (newest != null && newest.id != reviewed?.id) return LazarusCurrentCheck.Changed(newest)
    if (localCreatedAt != null && localCreatedAt > (reviewed?.created_at ?: Long.MIN_VALUE)) {
        return LazarusCurrentCheck.ChangedLocally(localCreatedAt)
    }
    if (answers.none { it.answered }) return LazarusCurrentCheck.Unconfirmed
    return LazarusCurrentCheck.Proceed(reviewed)
}

data class LazarusRecoveryDraft(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val created_at: Long
)

/**
 * Build the recovery event. The chosen candidate's item set is copied
 * verbatim, including encrypted private content (it stays encrypted to the
 * user's own key). It's dated after [current], the newest version known at
 * signing (the one the delta was computed against, never an older copy a
 * re-read found), even when a clobbering client's clock ran ahead, or relays
 * and caches would keep the clobbered one. The caller signs and publishes
 * this exactly once, on an explicit user click.
 */
fun buildLazarusRecoveryDraft(
    chosen: NostrEvent,
    current: NostrEvent? = null,
    now: Long = System.currentTimeMillis() / 1000
): LazarusRecoveryDraft = LazarusRecoveryDraft(
    kind = chosen.kind,
    content = chosen.content,
    tags = chosen.tags.map { it.toList() },
    created_at = maxOf(now, (current?.created_at ?: 0) + 1)
)
