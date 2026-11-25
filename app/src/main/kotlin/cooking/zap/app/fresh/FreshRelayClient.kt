package cooking.zap.app.fresh

import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

// Fresh's own connection to the curated feed relay (`wss://feed.zap.cooking`).
// Port of iOS `FreshRelayClient.swift` (zapcooking_ios #202).
//
// The contract is the iOS build spec §2 "Fresh feed relay — contract as
// measured"; where that section says "≠ web", this follows iOS. What this
// client does that the rest of the app's relay code does not:
//
// - Its own socket, through the [FreshSocketTransport] seam, never the shared
//   pool: nothing it opens joins another feed's subscriptions.
// - No signing key, at all. The relay sends a login challenge on connect and
//   again before every `CLOSED auth-required:`; those frames are dropped
//   unread, and the only frames this client ever sends are REQ and CLOSE.
// - Every event's id and signature are checked here; a bad one is dropped and
//   counted, never returned.
// - `auth-required:` is the free tier's edge ([FreshOutcome.Floor]), never an
//   error and never a prompt. `invalid:` on a topic preview means the slug is
//   unknown: that slug is never asked again by this client. A feed page,
//   recipes page or tail refused `invalid:` is reported but stays retryable.
// - The live tail survives a dropped socket: it is marked lost, revived by
//   [FreshRelayClient.foregrounded] (the view model calls it), and resumes
//   with an overlap, de-duplicated by id. The relay admitted one post 827 s
//   after its `created_at`, so resuming from "newest seen + 1" (what web
//   does) would lose posts like it.
//
// Nothing about viewing leaves the device: no request names the reader, and
// nothing here writes to a log.

/** How one request to the feed relay ended. */
sealed interface FreshOutcome {
    /** `EOSE`: the relay sent everything it has for the request. */
    data object Answered : FreshOutcome

    /**
     * The free tier ends here: `CLOSED auth-required:`, or a feed page whose
     * `until` is already older than the 14-day floor (not sent). Never an
     * error, never a login prompt.
     */
    data object Floor : FreshOutcome

    /**
     * `CLOSED invalid:`. For a topic preview (an unknown slug) it is
     * permanent: this client never asks that slug again. Any other request
     * refused this way may be sent again.
     */
    data class Invalid(val reason: String) : FreshOutcome

    /** Any other `CLOSED`, a dropped connection, or [FreshRelayClient.close]. */
    data class Failed(val reason: String) : FreshOutcome

    /** No frame for this request within the silence timeout. */
    data object TimedOut : FreshOutcome
}

/**
 * What a request returned. [events] is what arrived and verified, kept on
 * every outcome: posts that arrived before a failure or a timeout are real.
 *
 * @property events verified events, newest first (ties by id), not previously
 *   returned by this client's feed pages or live tail when the request is one
 *   of those.
 * @property received `EVENT` frames the relay sent for the request (the last
 *   one sent, for a feed page that stepped past a full boundary second).
 * @property rejected events dropped for a bad id, a bad signature or a
 *   malformed field.
 * @property asked the `limit` the deciding request asked for, when it had one.
 */
data class FreshAnswer(
    val events: List<NostrEvent>,
    val outcome: FreshOutcome,
    val received: Int = 0,
    val rejected: Int = 0,
    val asked: Int? = null,
) {
    /** A feed page the relay ended short: the free window has no more posts. */
    val isShortPage: Boolean
        get() = outcome == FreshOutcome.Answered && asked != null && received < asked

    /**
     * The `until` for the next feed page: the oldest post returned
     * (inclusive; the boundary post comes back once and is skipped).
     */
    val oldestCreatedAt: Long? get() = events.lastOrNull()?.created_at
}

/** One element of the live tail's flow. */
sealed interface FreshTailUpdate {
    /** A verified post not already returned by a page or the tail. */
    data class Event(val event: NostrEvent) : FreshTailUpdate

    /**
     * The connection carrying the tail dropped (or the relay closed it for a
     * non-verdict reason): nothing arrives until it is revived.
     */
    data object Lost : FreshTailUpdate

    /** Subscribed again, asking from [since] (newest seen minus the overlap). */
    data class Revived(val since: Long) : FreshTailUpdate

    /**
     * The relay refused the tail with a verdict ([FreshOutcome.Floor] or
     * [FreshOutcome.Invalid]). The flow completes after this.
     */
    data class Ended(val outcome: FreshOutcome) : FreshTailUpdate
}

/** The filter a Fresh request sends: exactly the fields Fresh uses. */
internal data class FreshFilter(
    val kinds: List<Int>,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
    val tTags: List<String>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("kinds") { kinds.forEach { add(it) } }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
        search?.let { put("search", it) }
        tTags?.let { tags -> putJsonArray("#t") { tags.forEach { add(it) } } }
    }
}

/** NIP-01 id and BIP-340 signature check for an event from the feed relay. */
object FreshEventVerifier {
    fun isValid(event: NostrEvent): Boolean {
        if (event.id.length != 64 || event.pubkey.length != 64 || event.sig.length != 128) return false
        if (event.id != event.id.lowercase()) return false
        // Recomputes the id from the event's fields, then checks the signature.
        return event.verifySignature()
    }
}

/**
 * See the file comment. Every member touches state on one confined
 * dispatcher (the Swift actor's equivalent), so the public functions suspend.
 *
 * @param now seconds since the epoch.
 * @param silenceTimeoutMs how long a request may go without a frame.
 * @param dispatcher confined to one thread at a time by this client.
 * @param monotonicMs a clock for the foreground grace period.
 */
class FreshRelayClient(
    private val url: String = FeedRelay.URL,
    private val transport: FreshSocketTransport = OkHttpFreshSocketTransport(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val silenceTimeoutMs: Long = 10_000,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    companion object {
        // Contract constants (iOS build spec §2 "Fresh feed relay").

        /** Notes, long-form, polls. Kind 35000 is not requested. */
        val KINDS: List<Int> = FreshPosts.FEED_KINDS

        /** The relay's free window for readers who are not logged in. */
        const val FREE_WINDOW_SECONDS = FreshPosts.FREE_WINDOW_SECONDS
        const val PAGE_SIZE = 30

        /** The relay's `max_limit` (NIP-11). */
        const val MAX_LIMIT = 500

        /** The relay answers a topic preview with at most this many posts. */
        const val PREVIEW_LIMIT = 5

        /**
         * The tags that make kind 30023 "recipes" on this relay. The relay
         * serves long-form at any age by kind, so the filter is what keeps
         * blog posts out.
         */
        val RECIPE_TAGS: List<String> = FreshPosts.RECIPE_TAGS.toList()

        /** Cap on the recipe pool (web's default). */
        const val MAX_RECIPES = 2000

        /**
         * How far before the newest post seen a revived tail asks from. The
         * relay delivered one post 827 s after its `created_at` (probe
         * 2026-11-10); an hour is more than four times that, and at the
         * window's measured rate (about 2 posts an hour) re-asking an hour
         * costs a couple of duplicates, which the id de-duplication drops.
         */
        const val TAIL_RESUME_OVERLAP_SECONDS = 60L * 60

        /** The backlog a (re)subscribed tail may return before it goes live. */
        const val TAIL_BACKLOG_LIMIT = 500

        /** Beyond this a relay ignoring `limit` can't grow one request unbounded. */
        const val MAX_EVENTS_PER_REQUEST = 1000

        /**
         * A drop seen this soon after the app came to the foreground revives
         * the tail at once: a background socket's death is often reported
         * only when the app resumes, after the foreground hook has run.
         */
        const val FOREGROUND_REVIVE_GRACE_MS = 10_000L

        /**
         * Clock skew allowed on a post's `created_at`. A tail's resume point
         * never moves past now plus this, so a future-dated post can't push
         * it ahead.
         */
        const val FUTURE_SKEW_SECONDS = 30L

        private const val FEED_KEY = "feed"
        private const val RECIPES_KEY = "recipes"
        private const val TOPIC_KEY_PREFIX = "topic:"
        private val INTEGER = Regex("-?[0-9]{1,19}")

        fun outcome(closedReason: String): FreshOutcome = when {
            closedReason.startsWith("auth-required:") -> FreshOutcome.Floor
            closedReason.startsWith("invalid:") -> FreshOutcome.Invalid(closedReason)
            else -> FreshOutcome.Failed(closedReason)
        }

        fun newestFirst(events: List<NostrEvent>): List<NostrEvent> =
            events.sortedWith(compareByDescending<NostrEvent> { it.created_at }.thenBy { it.id })

        private fun subscriptionId(): String =
            "fr-" + UUID.randomUUID().toString().replace("-", "").take(16)

        private fun reqFrame(subId: String, filter: FreshFilter): String = buildJsonArray {
            add("REQ")
            add(subId)
            add(filter.toJson())
        }.toString()

        private fun closeFrame(subId: String): String = buildJsonArray {
            add("CLOSE")
            add(subId)
        }.toString()

        /**
         * An event object from the relay, or null when a field is missing or
         * malformed. `created_at` and `kind` must be integer literals in
         * range: `1e100` or `1700000000.5` is null, never a conversion.
         */
        internal fun parseEvent(json: JsonObject): NostrEvent? {
            val tags = (json["tags"] as? JsonArray ?: return null).map { tag ->
                (tag as? JsonArray ?: return null).map { it.stringOrNull() ?: return null }
            }
            return NostrEvent(
                id = json["id"].stringOrNull() ?: return null,
                pubkey = json["pubkey"].stringOrNull() ?: return null,
                created_at = json["created_at"].integerOrNull() ?: return null,
                kind = json["kind"].integerOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                    ?: return null,
                tags = tags,
                content = json["content"].stringOrNull() ?: return null,
                sig = json["sig"].stringOrNull() ?: return null,
            )
        }

        private fun JsonElement?.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonElement?.integerOrNull(): Long? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive.isString || !INTEGER.matches(primitive.content)) return null
            return primitive.content.toLongOrNull()
        }
    }

    // State. Everything below is touched only on [confined].

    private val confined: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    private sealed interface Signal {
        val generation: Int

        data class Text(override val generation: Int, val text: String) : Signal
        data class Closed(override val generation: Int) : Signal
    }

    /**
     * Every socket callback, in arrival order, the close included. One
     * channel and one [consumer] for the client's lifetime, so a frame that
     * arrived before a drop is applied before the drop is.
     */
    private val signals = Channel<Signal>(Channel.UNLIMITED)
    private var consumer: Job? = null

    private var socket: FreshSocket? = null

    /** Bumped per socket so a finished socket's late frames are ignored. */
    private var generation = 0

    private class Pending(val key: String, val asked: Int?) {
        val answer = CompletableDeferred<FreshAnswer>()
        val events = ArrayList<NostrEvent>()
        val ids = HashSet<String>()
        var received = 0
        var rejected = 0
        var timeout: Job? = null
    }

    private val requests = LinkedHashMap<String, Pending>()

    /** One live tail. The object itself identifies it, so a finished flow can't stop a later tail. */
    private class Tail(val updates: Channel<FreshTailUpdate>, val startSince: Long) {
        var subId: String? = null

        /** Newest `created_at` delivered (verified, seen or not), clamped to now + skew. */
        var newest: Long = startSince
        var lost = false
    }

    private var tail: Tail? = null

    /** Ids returned by feed pages and the live tail (web's `seen`). */
    private val seen = HashSet<String>()

    /** Topic keys the relay answered `invalid:`; never asked again. Only previews are remembered. */
    private val invalidTopics = HashSet<String>()
    private var lastForegroundAtMs: Long? = null

    /**
     * The start of the free window, by this device's clock. The relay's own
     * clock decides; a disagreement shows up as [FreshOutcome.Floor] on a page.
     */
    val floor: Long get() = now() - FREE_WINDOW_SECONDS

    // Feed page

    /**
     * One page of the feed, newest first: `since` = the floor, [until]
     * inclusive (null = the newest). Posts already returned by this client
     * are skipped, so the boundary post a page repeats is dropped. A page
     * whose [until] is older than the floor is [FreshOutcome.Floor] without
     * a request.
     */
    suspend fun feedPage(until: Long? = null, limit: Int = PAGE_SIZE): FreshAnswer = withContext(confined) {
        val floor = floor
        if (until != null && until < floor) {
            return@withContext FreshAnswer(emptyList(), FreshOutcome.Floor)
        }
        val base = FreshFilter(kinds = KINDS, since = floor, until = until, limit = limit)
        var answer = request(base, FEED_KEY)
        var fresh = unseen(answer.events)
        // A full page of posts already shown: more posts share the boundary
        // second than fit on a page. Ask for that second again at the relay's
        // max; if even that is all shown, step past the second (web's rule).
        if (answer.outcome == FreshOutcome.Answered && fresh.isEmpty() && answer.received >= limit && until != null) {
            var rejectedSoFar = answer.rejected
            answer = request(base.copy(limit = MAX_LIMIT), FEED_KEY)
            answer = answer.copy(rejected = answer.rejected + rejectedSoFar)
            fresh = unseen(answer.events)
            if (answer.outcome == FreshOutcome.Answered && fresh.isEmpty() &&
                answer.received >= MAX_LIMIT && until - 1 >= floor
            ) {
                rejectedSoFar = answer.rejected
                answer = request(base.copy(until = until - 1), FEED_KEY)
                answer = answer.copy(rejected = answer.rejected + rejectedSoFar)
                fresh = unseen(answer.events)
            }
        }
        for (event in fresh) seen.add(event.id)
        answer.copy(events = newestFirst(fresh))
    }

    /** Forget what pages and the tail returned (a full refresh). */
    suspend fun resetSeen() = withContext(confined) {
        seen.clear()
    }

    // Topic preview

    /**
     * A topic's newest posts in the free window (at most five), for a
     * spotlight card. Not de-duplicated against the feed. An unknown slug is
     * [FreshOutcome.Invalid] and is never asked again by this client.
     */
    suspend fun topicPreview(slug: String): FreshAnswer = withContext(confined) {
        val search = TOPIC_KEY_PREFIX + slug
        val filter = FreshFilter(kinds = KINDS, since = floor, limit = PREVIEW_LIMIT, search = search)
        val answer = request(filter, key = search)
        answer.copy(events = newestFirst(answer.events))
    }

    // Recipes

    /**
     * Recipe long-form at any age: kind 30023 with the recipe `#t` filter and
     * no `since` (the one request without it). Paged with an inclusive
     * `until` at the relay's max until a page comes back short or adds
     * nothing, up to [max]. A page that does not end
     * [FreshOutcome.Answered] ends the walk with what was gathered so far.
     */
    suspend fun recipes(max: Int = MAX_RECIPES): FreshAnswer = withContext(confined) {
        val all = LinkedHashMap<String, NostrEvent>()
        var until: Long? = null
        var rejected = 0
        var last = FreshAnswer(emptyList(), FreshOutcome.Answered)
        while (all.size < max) {
            val filter = FreshFilter(kinds = listOf(30023), until = until, limit = MAX_LIMIT, tTags = RECIPE_TAGS)
            last = request(filter, RECIPES_KEY)
            rejected += last.rejected
            var added = 0
            for (event in last.events) {
                if (all.putIfAbsent(event.id, event) == null) added++
            }
            val oldest = last.events.minOfOrNull { it.created_at }
            if (last.outcome != FreshOutcome.Answered || last.received < MAX_LIMIT || added == 0 || oldest == null) break
            until = oldest
        }
        FreshAnswer(
            events = newestFirst(all.values.toList()), outcome = last.outcome,
            received = last.received, rejected = rejected, asked = last.asked,
        )
    }

    // Live tail

    /**
     * New posts from [since] on as they arrive. The tail starts when the
     * flow is collected. One tail per client: starting another ends the
     * previous one (its flow completes). The flow completes when the relay
     * refuses the tail with a verdict, or on [stopTail] / [close]; cancelling
     * the collector stops the tail and closes its subscription on the relay.
     * Updates are buffered without bound, so a slow collector loses nothing.
     */
    fun liveTail(since: Long): Flow<FreshTailUpdate> = flow {
        val updates = Channel<FreshTailUpdate>(Channel.UNLIMITED)
        val started = withContext(confined) { startTail(since, updates) }
        try {
            for (update in updates) emit(update)
        } finally {
            withContext(NonCancellable + confined) { stopTail(started) }
        }
    }

    /** True while a tail exists and its connection has dropped. */
    suspend fun tailIsLost(): Boolean = withContext(confined) { tail?.lost ?: false }

    /**
     * Subscribe a lost tail again, from the newest post it delivered minus
     * the overlap (never before the floor). True when revived.
     */
    suspend fun reviveTail(): Boolean = withContext(confined) { reviveLostTail() }

    suspend fun stopTail() = withContext(confined) {
        tail?.let { stopTail(it) }
    }

    private fun startTail(since: Long, updates: Channel<FreshTailUpdate>): Tail {
        tail?.let { stopTail(it) }
        val start = maxOf(since, floor)
        val started = Tail(updates, start)
        tail = started
        subscribeTail(start)
        return started
    }

    private fun reviveLostTail(): Boolean {
        val state = tail?.takeIf { it.lost } ?: return false
        val anchor = maxOf(state.startSince, state.newest)
        val since = maxOf(floor, anchor - TAIL_RESUME_OVERLAP_SECONDS)
        subscribeTail(since)
        state.updates.trySend(FreshTailUpdate.Revived(since))
        return true
    }

    private fun stopTail(stopping: Tail) {
        if (tail !== stopping) return
        tail = null
        val subId = stopping.subId
        if (subId != null && !stopping.lost) socket?.send(closeFrame(subId))
        stopping.updates.close()
    }

    // Lifecycle

    /**
     * Close the socket: pending requests end [FreshOutcome.Failed] with what
     * arrived, the tail stops, and no timer is left running. A later request
     * opens a new socket.
     */
    suspend fun close() = withContext(confined) {
        tail?.let { stopTail(it) }
        generation += 1
        tearDownSocket()
        for (subId in requests.keys.toList()) finishRequest(subId, FreshOutcome.Failed("closed"))
        consumer?.cancel()
        consumer = null
    }

    /** The app came to the foreground. Called by the view model; revives a lost tail. */
    suspend fun foregrounded() = withContext(confined) {
        lastForegroundAtMs = monotonicMs()
        reviveLostTail()
        Unit
    }

    // Requests

    /**
     * One request to `EOSE` or `CLOSED`, as an outcome. The timeout is for
     * silence: it restarts with every event, so a slow but steady page is
     * not cut off. A topic slug the relay answered `invalid:` before is not
     * asked again; every other request is always sent. If the caller is
     * cancelled while waiting, the subscription is closed on the relay and
     * forgotten here, so its late frames are ignored.
     */
    private suspend fun request(filter: FreshFilter, key: String): FreshAnswer {
        if (key in invalidTopics) {
            return FreshAnswer(emptyList(), FreshOutcome.Invalid("invalid: (refused earlier)"), asked = filter.limit)
        }
        val subId = subscriptionId()
        val pending = Pending(key, filter.limit)
        requests[subId] = pending
        armTimeout(subId, pending)
        send(reqFrame(subId, filter))
        try {
            return pending.answer.await()
        } catch (e: CancellationException) {
            if (requests.remove(subId) != null) {
                pending.timeout?.cancel()
                socket?.send(closeFrame(subId))
            }
            throw e
        }
    }

    private fun armTimeout(subId: String, pending: Pending) {
        pending.timeout?.cancel()
        pending.timeout = scope.launch {
            delay(silenceTimeoutMs)
            finishRequest(subId, FreshOutcome.TimedOut)
        }
    }

    private fun finishRequest(subId: String, outcome: FreshOutcome) {
        val pending = requests.remove(subId) ?: return
        pending.timeout?.cancel()
        when (outcome) {
            // End the subscription on the relay too. After a CLOSED or a
            // dropped socket there is nothing to close.
            FreshOutcome.Answered, FreshOutcome.TimedOut -> socket?.send(closeFrame(subId))
            is FreshOutcome.Invalid -> if (pending.key.startsWith(TOPIC_KEY_PREFIX)) invalidTopics.add(pending.key)
            FreshOutcome.Floor, is FreshOutcome.Failed -> {}
        }
        pending.answer.complete(
            FreshAnswer(
                events = pending.events.toList(), outcome = outcome,
                received = pending.received, rejected = pending.rejected, asked = pending.asked,
            )
        )
    }

    private fun subscribeTail(since: Long) {
        val state = tail ?: return
        val subId = subscriptionId()
        state.subId = subId
        state.lost = false
        send(reqFrame(subId, FreshFilter(kinds = KINDS, since = since, limit = TAIL_BACKLOG_LIMIT)))
    }

    private fun markTailLost() {
        val state = tail?.takeIf { !it.lost } ?: return
        state.lost = true
        state.subId = null
        state.updates.trySend(FreshTailUpdate.Lost)
    }

    // Socket

    private fun send(frame: String) {
        ensureConnected()
        socket?.send(frame)
    }

    private fun ensureConnected() {
        if (socket != null) return
        generation += 1
        val current = generation
        if (consumer?.isActive != true) {
            consumer = scope.launch {
                for (signal in signals) {
                    if (signal.generation != generation) continue
                    when (signal) {
                        is Signal.Text -> handle(signal.text)
                        is Signal.Closed -> connectionLost()
                    }
                }
            }
        }
        // Callbacks only enqueue: no coroutine per callback, order kept.
        socket = transport.open(url, object : FreshSocketListener {
            override fun onText(text: String) {
                signals.trySend(Signal.Text(current, text))
            }

            override fun onClosed() {
                signals.trySend(Signal.Closed(current))
            }
        })
    }

    private fun tearDownSocket() {
        socket?.cancel()
        socket = null
    }

    /**
     * The socket dropped (or never opened): pending requests fail with what
     * arrived, and the tail is lost.
     */
    private fun connectionLost() {
        generation += 1
        tearDownSocket()
        for (subId in requests.keys.toList()) finishRequest(subId, FreshOutcome.Failed("connection lost"))
        if (tail == null) return
        markTailLost()
        val at = lastForegroundAtMs
        if (at != null && monotonicMs() - at < FOREGROUND_REVIVE_GRACE_MS) {
            // Once per foreground: a socket that can't open must not loop.
            lastForegroundAtMs = null
            reviveLostTail()
        }
    }

    // Frames

    private fun handle(text: String) {
        val frame = try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: return
        when (frame.getOrNull(0).stringOrNull()) {
            "EVENT" -> {
                val subId = frame.getOrNull(1).stringOrNull() ?: return
                val json = frame.getOrNull(2) as? JsonObject ?: return
                val pending = requests[subId]
                if (pending != null) {
                    receiveRequestEvent(subId, pending, json)
                } else if (subId == tail?.subId) {
                    receiveTailEvent(json)
                }
            }
            // A tail's EOSE ends its backlog; it stays open.
            "EOSE" -> finishRequest(frame.getOrNull(1).stringOrNull() ?: return, FreshOutcome.Answered)
            "CLOSED" -> {
                val subId = frame.getOrNull(1).stringOrNull() ?: return
                val outcome = outcome(frame.getOrNull(2).stringOrNull() ?: "")
                if (subId in requests) {
                    finishRequest(subId, outcome)
                } else if (subId == tail?.subId) {
                    tailClosed(outcome)
                }
            }
            // The relay's login challenge is dropped unread: this client
            // never logs in. NOTICE, OK and anything else settle nothing.
            else -> {}
        }
    }

    private fun receiveRequestEvent(subId: String, pending: Pending, json: JsonObject) {
        pending.received += 1
        if (pending.received <= MAX_EVENTS_PER_REQUEST) {
            val event = parseEvent(json)?.takeIf { FreshEventVerifier.isValid(it) }
            if (event == null) {
                pending.rejected += 1
            } else if (pending.ids.add(event.id)) {
                pending.events.add(event)
            }
        }
        armTimeout(subId, pending)
    }

    private fun receiveTailEvent(json: JsonObject) {
        val state = tail ?: return
        val event = parseEvent(json)?.takeIf { FreshEventVerifier.isValid(it) } ?: return
        // Clamped: a future-dated post must not move the resume point ahead.
        val createdAt = minOf(event.created_at, now() + FUTURE_SKEW_SECONDS)
        if (createdAt > state.newest) state.newest = createdAt
        if (!seen.add(event.id)) return
        state.updates.trySend(FreshTailUpdate.Event(event))
    }

    private fun tailClosed(outcome: FreshOutcome) {
        when (outcome) {
            FreshOutcome.Floor, is FreshOutcome.Invalid -> {
                // A verdict ends this tail; a later tail may ask again.
                val state = tail ?: return
                state.updates.trySend(FreshTailUpdate.Ended(outcome))
                state.subId = null
                stopTail(state)
            }
            // Not a verdict on the request: treat as a lost connection.
            FreshOutcome.Answered, is FreshOutcome.Failed, FreshOutcome.TimedOut -> markTailLost()
        }
    }

    private fun unseen(events: List<NostrEvent>): List<NostrEvent> = events.filter { it.id !in seen }
}
