package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.FoodHashtags
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.MuteRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OnlyFood 🍳 — a kind-1 social food feed over the expanded [FoodHashtags] set
 * (concern 1.6). Modes (v1): [Mode.GLOBAL] and [Mode.FOLLOWING] (server-side
 * `authors` filter from the kind-3 contacts). Members + replies deferred.
 * Filtering is mute-only (matches the web + `HashtagFeedViewModel`).
 *
 * **Per-mode cache — DON'T re-query on toggle.** `search.nostrarchives.com`
 * rate-limits repeated queries per connection: the first identical query
 * returns ~99 events, a repeat ~12s later returns 0. So each mode is queried
 * **once** and its results cached in a [ModeState]; toggling [setMode] swaps
 * the visible list to the target mode's cache **instantly, with no relay
 * query**. The only path that re-queries a loaded mode is explicit
 * [refresh] (pull-to-refresh). A mode that legitimately returns 0 still gets
 * `loaded = true`, so it isn't re-queried (and re-throttled) on every toggle.
 *
 * Churn hygiene (less throttle pressure): all loads serialize through one
 * [submit] that `cancelAndJoin`s the previous job first, and teardown CLOSEs
 * only the subIds actually opened.
 */
class OnlyFoodFeedViewModel : ViewModel() {

    enum class Mode { GLOBAL, FOLLOWING }

    private class ModeState {
        val seen = LinkedHashMap<String, NostrEvent>()
        var loaded = false
        var endReached = false
        var emptyFollows = false
        fun snapshot(): List<NostrEvent> = seen.values.sortedByDescending { it.created_at }
    }

    private enum class Load { INITIAL, PAGE, REFRESH }

    private val states = mapOf(Mode.GLOBAL to ModeState(), Mode.FOLLOWING to ModeState())
    private fun stateOf(mode: Mode) = states.getValue(mode)

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _mode = MutableStateFlow(Mode.GLOBAL)
    val mode: StateFlow<Mode> = _mode

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isPaging = MutableStateFlow(false)
    val isPaging: StateFlow<Boolean> = _isPaging

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _emptyFollows = MutableStateFlow(false)
    val emptyFollows: StateFlow<Boolean> = _emptyFollows

    private var deps: Deps? = null
    private var activeJob: Job? = null

    private class Deps(
        val relayPool: RelayPool,
        val eventRepo: EventRepository,
        val muteRepo: MuteRepository,
        val contactRepo: ContactRepository,
    )

    fun init(
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository,
        contactRepo: ContactRepository,
    ) {
        if (deps != null) return
        deps = Deps(relayPool, eventRepo, muteRepo, contactRepo)
        // Seed GLOBAL from cache so cached food renders before the live query.
        seedGlobalFromCache()
        // Auto-recover: re-issue when the search relay (re)connects while the
        // current mode hasn't reached a genuine EOSE. Started before the initial
        // submit so a connect that lands during the first query is observed.
        observeReconnect()
        val st = stateOf(_mode.value)
        if (!st.loaded) submit(_mode.value, st, Load.INITIAL, since = null, until = null)
    }

    /** Instant cache swap. Queries the target mode only if it's never loaded. */
    fun setMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        val st = stateOf(mode)
        _notes.value = st.snapshot()
        _emptyFollows.value = st.emptyFollows
        _isPaging.value = false
        _isRefreshing.value = false
        if (st.loaded) {
            _isLoading.value = false
        } else {
            submit(mode, st, Load.INITIAL, since = null, until = null)
        }
    }

    /** The ONLY path that re-queries a loaded mode. Merges newest into cache. */
    fun refresh() {
        val mode = _mode.value
        val st = stateOf(mode)
        // A refresh re-opens paging: a prior `endReached` may have been a quiet
        // stretch or a throttle, and new posts may have arrived. `endReached` is
        // per-mode and resettable here; it is NOT the Phase-1 `loaded` latch.
        st.endReached = false
        submit(mode, st, Load.REFRESH, since = null, until = null)
    }

    /**
     * Infinite-scroll: page strictly backwards from the oldest loaded event,
     * appending to the cache. No `since` floor — `until` + `limit` walk backwards
     * through quiet stretches the standard Nostr way, so a single empty time
     * window no longer ends the feed. `endReached` then trips only on a genuine
     * zero-older-events EOSE.
     */
    fun loadMore() {
        val mode = _mode.value
        val st = stateOf(mode)
        if (_isLoading.value || _isPaging.value || _isRefreshing.value || st.endReached) return
        val oldest = st.seen.values.minOfOrNull { it.created_at } ?: return
        val bounds = pageBoundsBehind(oldest)
        submit(mode, st, Load.PAGE, since = bounds.since, until = bounds.until)
    }

    /**
     * Single serialized entry point. Captures [mode]/[state] at call-time (so
     * a mid-flight mode switch can't mis-route results), `cancelAndJoin`s the
     * previous job before the next REQ, and merges results into [state]'s
     * cache — updating the visible [_notes] only while [mode] is current.
     */
    private fun submit(mode: Mode, state: ModeState, load: Load, since: Long?, until: Long?) {
        val previous = activeJob
        activeJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val d = deps ?: return@launch

            val follows: Set<String>? = if (mode == Mode.FOLLOWING) {
                d.contactRepo.getFollowList().map { it.pubkey }.toSet()
            } else null
            if (follows != null && follows.isEmpty()) {
                state.loaded = true
                state.emptyFollows = true
                if (_mode.value == mode) {
                    _emptyFollows.value = true
                    clearIndicators()
                }
                return@launch
            }
            state.emptyFollows = false
            if (_mode.value == mode) {
                _emptyFollows.value = false
                when (load) {
                    Load.INITIAL -> _isLoading.value = true
                    Load.PAGE -> _isPaging.value = true
                    Load.REFRESH -> _isRefreshing.value = true
                }
            }

            val searchRelay = SearchViewModel.DEFAULT_SEARCH_RELAY
            // Pull-to-refresh must punch through a stale cooldown so it never
            // silently no-ops. INITIAL/auto-recover must NOT clear it — that would
            // fight a genuine 429 backoff from the rate-limit-prone search relay.
            if (load == Load.REFRESH) d.relayPool.clearCooldown(searchRelay)

            val base = "onlyfood-${SUB_SEQ.incrementAndGet()}"
            val opened = mutableListOf<String>()
            var received = 0
            var collector: Job? = null
            // Pin while the read is in flight so the LRU cap can't evict the
            // search ephemeral mid-handshake; unpinned in finally.
            d.relayPool.pinEphemeral(searchRelay)
            try {
                // Gate the REQ on a LIVE socket. Connect budget is separate from
                // the EOSE budget — we don't race one 8s timeout across a cold
                // connect AND the EOSE.
                val connected = d.relayPool.awaitRelayConnected(searchRelay, CONNECT_TIMEOUT_MS)
                if (!connected) {
                    // Connect timed out. A queued send would drain only after this
                    // collector is gone (relayEvents has replay=0) → events arrive
                    // into the void, recreating the original bug. So do NOT send;
                    // leave loaded=false for auto-recover/refresh to retry.
                    if (_mode.value == mode) clearIndicators()
                    return@launch
                }

                // Subscribe the event collector AND the EOSE awaiter, and wait
                // until BOTH are actively collecting before sending — relayEvents
                // and eoseSignals both have replay=0, so a send that races ahead of
                // subscription drops its events/EOSE on the floor.
                val collectorReady = CompletableDeferred<Unit>()
                collector = launch {
                    d.relayPool.relayEvents
                        .onSubscription { collectorReady.complete(Unit) }
                        .collect { relayEvent ->
                            if (!relayEvent.subscriptionId.startsWith(base)) return@collect
                            val event = relayEvent.event
                            if (event.kind != 1 || event.id in state.seen) return@collect
                            if (!accept(event, d, follows)) return@collect
                            received++
                            state.seen[event.id] = event
                            d.eventRepo.cacheEvent(event)
                            d.eventRepo.requestProfileIfMissing(event.pubkey)
                            if (_mode.value == mode) _notes.value = state.snapshot()
                        }
                }
                val eoseReady = CompletableDeferred<Unit>()
                val eoseAwaiter = async {
                    withTimeoutOrNull(EOSE_TIMEOUT_MS) {
                        d.relayPool.eoseSignals
                            .onSubscription { eoseReady.complete(Unit) }
                            .first { it.startsWith(base) }
                    }
                }
                collectorReady.await()
                eoseReady.await()

                val filter = Filter(
                    kinds = listOf(1),
                    tTags = FoodHashtags.ALL,
                    since = since,
                    until = until,
                    limit = 100,
                )
                var anySent = false
                if (follows == null) {
                    opened.add(base)
                    if (d.relayPool.sendToRelayOrEphemeral(searchRelay, ClientMessage.req(base, filter))) {
                        anySent = true
                    }
                } else {
                    follows.toList().chunked(AUTHOR_CHUNK).forEachIndexed { i, chunk ->
                        val subId = "$base-$i"
                        opened.add(subId)
                        if (d.relayPool.sendToRelayOrEphemeral(
                                searchRelay,
                                ClientMessage.req(subId, filter.copy(authors = chunk)),
                            )
                        ) {
                            anySent = true
                        }
                    }
                }

                if (!anySent) {
                    // Every send was dropped (cooldown / no capacity). Don't await
                    // an EOSE that can't arrive; leave loaded=false to retry.
                    eoseAwaiter.cancel()
                    if (_mode.value == mode) clearIndicators()
                    return@launch
                }

                // Latch ONLY on a genuine EOSE (even at 0 events). A timeout leaves
                // loaded=false so auto-recover/refresh can retry — no empty latch.
                // By here connected and anySent are both true (the paths above
                // early-returned otherwise); [shouldLatchLoaded] is the single
                // source of truth for the rule, unit-tested across all three cases.
                val eose = eoseAwaiter.await()
                val eoseFired = eose != null
                if (shouldLatchLoaded(connected = true, anySent = true, eoseFired = eoseFired)) {
                    state.loaded = true
                    // Gated on a genuine EOSE (a timeout never end-reaches): a PAGE
                    // that returned zero strictly-older events has hit the floor.
                    if (load == Load.PAGE && pageEndReached(received)) state.endReached = true
                }
                if (_mode.value == mode) {
                    _notes.value = state.snapshot()
                    clearIndicators()
                }
                if (eose != null) delay(6_000) // collect stragglers, then tear down
            } finally {
                collector?.cancel()
                d.relayPool.unpinEphemeral(searchRelay)
                // Close ONLY the subIds we actually opened (not a base-0..39 sweep).
                for (subId in opened) d.relayPool.closeOnAllRelays(subId)
            }
        }
    }

    /**
     * Cache-seed the GLOBAL mode from [EventRepository.cachedFoodNotes] so cached
     * food renders immediately instead of a blank empty-state. Mute-filtered via
     * [accept] (follows=null), inserted into GLOBAL's `seen` — NOT into [_notes],
     * which is rebuilt from `seen` on the first live snapshot. Does NOT set
     * `loaded`, so the live query still runs and EOSE still drives the latch.
     * GLOBAL only: FOLLOWING needs the follow set first.
     */
    private fun seedGlobalFromCache() {
        val d = deps ?: return
        viewModelScope.launch {
            val global = stateOf(Mode.GLOBAL)
            if (global.seen.isNotEmpty()) return@launch
            val cached = withContext(Dispatchers.IO) { d.eventRepo.cachedFoodNotes() }
            var added = false
            for (event in cached) {
                if (event.kind != 1 || event.id in global.seen) continue
                if (!accept(event, d, follows = null)) continue
                global.seen[event.id] = event
                added = true
            }
            if (added && _mode.value == Mode.GLOBAL) _notes.value = global.snapshot()
        }
    }

    /**
     * Auto-recover: each [RelayPool.connectedCount] change is a cheap "re-check"
     * tick. Re-derive `isRelayConnected(searchRelay)` fresh each time (the search
     * ephemeral is evicted/recreated, so a held per-URL flow would observe a dead
     * Relay). Re-issue ONLY when the search relay is connected, the current mode
     * has NOT reached a genuine EOSE (`!loaded`), and nothing is in flight.
     *
     * Keying on `!loaded` (not `notes.isEmpty()`) is deliberate: a legitimately
     * empty-but-loaded GLOBAL window, a FOLLOWING mode whose follows don't post
     * food, and the `emptyFollows` fast-path all set `loaded = true`, so they are
     * NOT re-queried on unrelated relay reconnects — the throttle protection the
     * per-mode cache exists for. `loaded` flips true after the first EOSE, so this
     * self-limits with no false→true edge tracking.
     */
    private fun observeReconnect() {
        val d = deps ?: return
        viewModelScope.launch {
            d.relayPool.connectedCount.collect {
                if (!d.relayPool.isRelayConnected(SearchViewModel.DEFAULT_SEARCH_RELAY)) return@collect
                val mode = _mode.value
                val st = stateOf(mode)
                if (st.loaded || activeJob?.isActive == true) return@collect
                submit(mode, st, Load.INITIAL, since = null, until = null)
            }
        }
    }

    private fun clearIndicators() {
        _isLoading.value = false
        _isPaging.value = false
        _isRefreshing.value = false
    }

    private fun accept(event: NostrEvent, d: Deps, follows: Set<String>?): Boolean {
        if (follows != null && event.pubkey !in follows) return false
        if (d.muteRepo.isBlocked(event.pubkey)) return false
        if (d.muteRepo.containsMutedWord(event.content)) return false
        return true
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }

    companion object {
        /** Process-wide subId sequence — unique across all VM instances. */
        private val SUB_SEQ = java.util.concurrent.atomic.AtomicLong(0)
        private const val AUTHOR_CHUNK = 500
        /** Budget to bring the search ephemeral's socket up before sending. */
        private const val CONNECT_TIMEOUT_MS = 8_000L
        /** Budget to await EOSE, measured only AFTER the socket is connected. */
        private const val EOSE_TIMEOUT_MS = 8_000L
    }
}

/**
 * The OnlyFood per-mode latch rule, extracted pure for unit testing. A mode is
 * "loaded" (so it won't be re-queried on toggle or auto-recover) ONLY when the
 * socket was [connected], at least one REQ was actually [anySent], AND a genuine
 * EOSE arrived ([eoseFired]). EOSE-with-zero-events still latches (the window is
 * genuinely empty); a connect/send failure or an EOSE timeout does NOT latch, so
 * the transient failure can be retried instead of latching a blank feed.
 */
internal fun shouldLatchLoaded(connected: Boolean, anySent: Boolean, eoseFired: Boolean): Boolean =
    connected && anySent && eoseFired

/** Bounds for one backward infinite-scroll page. */
internal data class PageBounds(val since: Long?, val until: Long)

/**
 * The bounds for the next page strictly older than [oldestCreatedAt]. NO `since`
 * floor (the Phase-2 fix): `until` + `limit` walk backwards through quiet
 * stretches instead of a fixed time window ending the feed at the first gap.
 * `until = oldestCreatedAt - 1` excludes the boundary second, so each non-empty
 * page strictly lowers `oldest` → the next `until` strictly decreases → no two
 * page queries are identical (search-relay throttle-safe).
 */
internal fun pageBoundsBehind(oldestCreatedAt: Long): PageBounds =
    PageBounds(since = null, until = oldestCreatedAt - 1)

/**
 * Paging exhaustion rule: a backward page has hit the floor ONLY when it added
 * zero strictly-older events. Because `until = oldest - 1` excludes everything at
 * or above `oldest`, every returned event is genuinely new, so [receivedNew] == 0
 * means the relay holds nothing older — a real end, not an empty intermediate
 * window. Always evaluated behind a genuine EOSE (a timeout never end-reaches).
 */
internal fun pageEndReached(receivedNew: Int): Boolean = receivedNew == 0
