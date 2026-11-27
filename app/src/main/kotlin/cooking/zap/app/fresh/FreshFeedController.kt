package cooking.zap.app.fresh

import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the Fresh list as the divider sees it. Special cards are a later step. */
data class FreshRow(val event: NostrEvent) : FreshDividable {
    val id: String get() = event.id
    override val isSpecialCard: Boolean get() = false
    override val dividerCreatedAt: Long get() = event.created_at
}

/** The live tail as the list sees it. */
enum class FreshTailStatus {
    /** Not running: before the first page, or while the feed is not shown. */
    IDLE,
    LIVE,

    /** The connection dropped; a revive is pending (foreground, refresh, or the retry with backoff). */
    LOST,

    /** The relay refused the tail with a verdict. A refresh or re-showing the feed starts a new one. */
    ENDED,
}

/**
 * What the Fresh list shows. One immutable value per change.
 *
 * @property posts newest first: the relay's posts (author-spaced) with the
 *   user's own recent posts placed by time.
 * @property rows [posts] with the "new since your last visit" divider placed.
 * @property loadFailed the first page failed or timed out with nothing to show.
 * @property finishLine the end of the free window was reached.
 * @property recipePicks the recipe row after the caught-up card: recipes from
 *   the relay, newest first, one per coordinate, minus any recipe [posts]
 *   already shows (by id or by coordinate). Loaded and exposed; nothing
 *   renders it yet.
 * @property visitMark this session's previous-visit mark ([FreshLastVisit]).
 * @property pendingNewCount tail posts held back. They are never put into
 *   the list on their own: [FreshFeedController.showPending] inserts them.
 * @property showsPendingPill posts wait that the reader has not dismissed.
 */
data class FreshFeedState(
    val posts: List<NostrEvent> = emptyList(),
    val rows: List<FreshDividedRow<FreshRow>> = emptyList(),
    val isLoading: Boolean = false,
    val isPaging: Boolean = false,
    val isRefreshing: Boolean = false,
    val loadFailed: Boolean = false,
    val hasLoaded: Boolean = false,
    val finishLine: FreshFinishLine = FreshFinishLine.NONE,
    val recipePicks: List<NostrEvent> = emptyList(),
    val visitMark: Long? = null,
    val tailStatus: FreshTailStatus = FreshTailStatus.IDLE,
    val pendingNewCount: Int = 0,
    val showsPendingPill: Boolean = false,
) {
    val isEmpty: Boolean get() = hasLoaded && posts.isEmpty() && !loadFailed

    /**
     * The "nothing yet" message. Once the end is reached the list shows
     * instead, so the caught-up line and the recipe row are visible even when
     * no post is.
     */
    val showsEmptyMessage: Boolean get() = isEmpty && !finishLine.caughtUp && pendingNewCount == 0

    /** Page 1 failed with nothing to show. Posts held back show the list instead. */
    val showsFailureMessage: Boolean get() = loadFailed && posts.isEmpty() && pendingNewCount == 0
}

/**
 * The Fresh feed: the curated relay `wss://feed.zap.cooking`, free tier,
 * read-only (build spec §1 "Fresh feed relay"). Port of iOS
 * `FreshFeedViewModel.swift` without the special cards. Behind
 * `FeatureFlags.FRESH_FEED_ENABLED`.
 *
 * Composes [FreshRelayClient] (own socket, no AUTH, verified events),
 * [FreshPosts] (filters), [FreshSpacing], [FreshLastVisit] and
 * [FreshFinishLine]. What this type adds:
 * - **Hiding.** Mutes and the hellthread rule apply through [rules], read on
 *   every use, so a block takes effect on the next post without a snapshot.
 *   [rulesChanged] drops what is already shown. Web of Trust does not apply.
 *   Deleted posts go at ingest and when [deletions] names one.
 * - **Own posts.** A new user is not on the curated list, so their post never
 *   comes back from the relay. Their own posts of the last 14 days are pinned
 *   from local sources ([ownLocalPosts], reloaded on refresh, and
 *   [published]), in time order; the relay's copy replaces one by id, and
 *   long-form keeps the newest per coordinate. An own post must pass [verify]
 *   and must not be private, so the unsigned synthetic copy of a private
 *   reply never shows, before or after a restart. Only the pubkey is needed:
 *   nothing here takes or asks for a signer.
 * - **Lifecycle.** The socket is open only while Fresh is the selected feed.
 *   Leaving bumps the epoch: work in flight is dropped before it can send a
 *   request or apply a result, and the next connection waits for the close.
 *   A first load cut short this way starts again on the next activation.
 * - **The tail.** Started after page 1 from the newest post (clamped to now
 *   plus the skew), closed when the feed goes away, revived on foreground,
 *   restarted with an overlap on refresh, and retried with backoff while the
 *   app stays in the foreground. A tail post waits behind [FreshFeedState.pendingNewCount]
 *   until [showPending]; an edit of a long-form post already in the list
 *   replaces it in place at once.
 *
 * Nothing about viewing leaves the device: the client names no reader, and
 * the last-visit mark is device-local. An event is handed to [persist] and
 * [onShown] with no relay URL: the feed relay is never recorded as where a
 * post came from.
 *
 * Every member runs on one confined dispatcher. The public functions return
 * the [Job] that does the work.
 *
 * @param rules null means no mute list is loaded: nothing is muted.
 * @param isDeleted the app's NIP-09 record, for one event.
 * @param persist keep these events so a thread opens without another lookup.
 * @param onShown rows that entered the list (or the recipe pool) for the
 *   first time: the caller asks for their authors' missing profiles.
 * @param rulesChanged the reader's mutes changed.
 * @param deletions ids of events deleted just now.
 */
class FreshFeedController(
    val pubkey: String,
    private val client: FreshRelayClient = FreshRelayClient(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val rules: () -> FreshReaderRules? = { null },
    private val isPrivate: (eventId: String) -> Boolean = { false },
    private val isDeleted: (NostrEvent) -> Boolean = { false },
    private val verify: (NostrEvent) -> Boolean = FreshEventVerifier::isValid,
    private val ownLocalPosts: suspend () -> List<NostrEvent> = { emptyList() },
    private val persist: (List<NostrEvent>) -> Unit = {},
    private val onShown: (List<NostrEvent>) -> Unit = {},
    private val visitStorage: FreshVisitStorage? = null,
    private val lastVisit: FreshLastVisit = FreshLastVisit.shared,
    private val published: Flow<NostrEvent> = emptyFlow(),
    private val rulesChanged: Flow<Unit> = emptyFlow(),
    private val deletions: Flow<String> = emptyFlow(),
    retryDelaysMs: List<Long> = TAIL_RETRY_DELAYS_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    companion object {
        const val RECIPE_ROW_LIMIT = 12

        /** How many recipes are kept for the row, before excluding the feed's. */
        const val RECIPE_POOL_LIMIT = 60

        /** Paging starts this many posts before the end. */
        const val LOAD_MORE_PREFETCH = 6

        /**
         * Waits before each in-foreground revive of a lost tail. The last
         * value repeats; any delivered post starts the sequence again.
         */
        val TAIL_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)

        /**
         * A full page whose posts are all filtered out adds no row to scroll
         * to; this many such pages in a row are fetched without a scroll.
         */
        const val MAX_EMPTY_PAGE_HOPS = 5

        /** How many own posts per kind are read from local storage. */
        const val OWN_POSTS_LIMIT = 100

        /**
         * A note that replies to nothing (NIP-10, mentions ignored). Own
         * replies are never pinned: they belong to their thread. Long-form
         * and polls are always top-level.
         */
        fun isTopLevel(event: NostrEvent): Boolean =
            event.kind != 1 || Nip10.getReplyTarget(event) == null

        /**
         * The optimistic "publishing" row is shown only for kinds Fresh keeps
         * (notes, long-form, polls): a gallery post would vanish when its
         * published event arrives, since Fresh can't pin it.
         */
        fun showsPendingRow(kind: Int): Boolean = kind in FreshPosts.FEED_KINDS

        /**
         * The 0-based position among posts of the post at [rowIndex], or null
         * when that row is the divider or out of range.
         */
        fun postPosition(rowIndex: Int, rows: List<FreshDividedRow<FreshRow>>): Int? {
            if (rows.getOrNull(rowIndex) !is FreshDividedRow.Row) return null
            return rows.subList(0, rowIndex).count { it is FreshDividedRow.Row }
        }

        /** `kind:pubkey:d`, the address an edit shares with what it replaces. */
        fun coordinate(event: NostrEvent): String {
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            return "${event.kind}:${event.pubkey}:$d"
        }

        /**
         * The recipe row: [pool] minus any recipe [feed] already shows, by id
         * or by coordinate (another edit of the same recipe counts as shown).
         */
        fun recipePicks(pool: List<NostrEvent>, feed: List<NostrEvent>, limit: Int = RECIPE_ROW_LIMIT): List<NostrEvent> {
            val ids = feed.mapTo(HashSet()) { it.id }
            val coordinates = feed.filter { it.kind == 30023 }.mapTo(HashSet()) { coordinate(it) }
            return pool.filter { it.id !in ids && coordinate(it) !in coordinates }.take(limit)
        }
    }

    private val confined: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val retryDelays = retryDelaysMs.ifEmpty { TAIL_RETRY_DELAYS_MS }

    private val _state = MutableStateFlow(FreshFeedState())
    val state: StateFlow<FreshFeedState> = _state

    // State. Everything below is touched only on [confined].

    private var observing = false

    /** Fresh is the selected feed (its list is shown). */
    private var selected = false

    /** The app is in the foreground. */
    private var appActive = true

    /** Bumped on every deactivation; work in flight captures it and stops when it changes. */
    private var epoch = 0

    /** The close started by the last deactivation; new connections wait for it. */
    private var pendingClose: Job? = null

    /** The relay's posts in display order (spaced; tail posts placed by time). */
    private val relayPosts = ArrayList<NostrEvent>()

    /** Tail posts held back, in arrival order. */
    private val pendingNew = ArrayList<NostrEvent>()

    /** How many held posts the reader dismissed the pill for; it comes back when more arrive. */
    private var pillDismissedAt = 0

    /** Own posts from local sources, by id. */
    private val ownPosts = HashMap<String, NostrEvent>()

    /** What the list shows now (the last [emit]). */
    private var posts: List<NostrEvent> = emptyList()
    private var recipePool: List<NostrEvent> = emptyList()

    /** Ids already handed to [onShown]. */
    private val announced = HashSet<String>()

    /**
     * `until` for the next page: the oldest event the last page returned,
     * whatever the filters kept (so a page of muted posts can't stall paging).
     */
    private var pageCursor: Long? = null
    private var recipesLoaded = false
    private var isLoading = false
    private var isPaging = false
    private var refreshing = false
    private var loadFailed = false
    private var hasLoaded = false
    private var finishLine = FreshFinishLine.NONE
    private var visitMark: Long? = null
    private var tailStatus = FreshTailStatus.IDLE
    private var retryAttempt = 0

    private var tailJob: Job? = null
    private var retryJob: Job? = null
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var recipeJob: Job? = null
    private var refreshJob: Job? = null

    // Lifecycle

    /**
     * Fresh became the selected feed. Until page 1 has loaded: load it
     * (again, if a previous attempt was cut short), then start the tail.
     * After that: start the tail again; the list is kept and no page is
     * re-requested.
     */
    fun activate(): Job = scope.launch {
        if (selected) return@launch
        selected = true
        if (!observing) {
            observing = true
            observe()
        }
        if (!hasLoaded) {
            if (loadJob == null) {
                val epoch = epoch
                isLoading = true
                loadJob = scope.launch { loadFirstPage(epoch) }
            }
        } else {
            startTail(tailAnchor() - FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS)
            if (finishLine.caughtUp && !recipesLoaded) startRecipes()
        }
        publish()
    }

    /**
     * Fresh went away (another feed picked, or the screen closed): work in
     * flight is abandoned, the tail stops and the socket closes. The list is
     * kept.
     */
    fun deactivate(): Job = scope.launch {
        if (!selected) return@launch
        selected = false
        epoch += 1
        stopTail()
        for (job in listOf(loadJob, pageJob, recipeJob, refreshJob)) job?.cancel()
        loadJob = null
        pageJob = null
        recipeJob = null
        refreshJob = null
        isLoading = false
        isPaging = false
        refreshing = false
        val previous = pendingClose
        pendingClose = scope.launch {
            previous?.join()
            client.close()
        }
        publish()
        pendingClose?.join()
    }

    /**
     * The app came to the foreground. Forwarded whatever feed is selected,
     * so [appActive] is always current. Revives a lost tail at once.
     */
    fun foregrounded(): Job = scope.launch {
        appActive = true
        if (!selected) return@launch
        val epoch = epoch
        client.foregrounded()
        // Page 1 (or a refresh) starts the tail itself when it finishes.
        if (epoch != this@FreshFeedController.epoch || !selected || !hasLoaded || loadJob != null || refreshing) return@launch
        retryAttempt = 0
        when (tailStatus) {
            FreshTailStatus.LOST -> {
                // The client may have revived it just now; its update is on the way.
                if (client.tailIsLost() && !client.reviveTail() && epoch == this@FreshFeedController.epoch && selected) {
                    startTail(tailAnchor() - FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS)
                }
            }
            FreshTailStatus.ENDED, FreshTailStatus.IDLE ->
                startTail(tailAnchor() - FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS)
            FreshTailStatus.LIVE -> {}
        }
        publish()
    }

    /** Background: no in-foreground retries while suspended. */
    fun backgrounded(): Job = scope.launch {
        appActive = false
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Pull-to-refresh: the tail is stopped first (so nothing it delivers is
     * marked seen and then lost), page 1 is loaded from scratch with own
     * posts reloaded from local storage, and a new tail starts from the new
     * page with the overlap. A refresh that fails keeps the list.
     */
    fun refresh(): Job = scope.launch {
        if (!hasLoaded) {
            // Nothing to refresh yet: this is the first load.
            if (selected) loadJob?.join()
            return@launch
        }
        if (!selected || refreshing) return@launch
        refreshing = true
        publish()
        val job = scope.launch { refreshNow(epoch) }
        refreshJob = job
        job.join()
        if (refreshJob === job) {
            refreshJob = null
            refreshing = false
            publish()
        }
    }

    private suspend fun refreshNow(epoch: Int) {
        stopTail()
        pageJob?.cancel()
        pageJob = null
        recipeJob?.cancel()
        recipeJob = null
        client.stopTail()
        client.resetSeen()
        if (epoch != this.epoch) return
        loadOwnLocalPosts()
        if (epoch != this.epoch) return
        val applied = fetchPages(first = true, epoch = epoch, refresh = true)
        if (!applied || epoch != this.epoch || !selected) return
        retryAttempt = 0
        startTail(tailAnchor() - FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS)
        publish()
    }

    /** Close the socket and stop everything. The controller is not used again. */
    fun close(): Job {
        val closing = CoroutineScope(confined).launch {
            withContext(NonCancellable) { client.close() }
        }
        scope.cancel()
        return closing
    }

    // Paging

    /**
     * A row appeared. [rowIndex] is its index in [FreshFeedState.rows], which
     * also hold the divider, so the threshold counts the posts up to that
     * row: comparing the row index with the post count would page one post
     * early per non-post row above.
     */
    fun rowAppeared(rowIndex: Int): Job = scope.launch {
        val position = postPosition(rowIndex, _state.value.rows) ?: return@launch
        if (position >= posts.size - LOAD_MORE_PREFETCH) startPage()
    }

    fun loadMore(): Job = scope.launch {
        startPage()?.join()
    }

    private fun startPage(): Job? {
        val canPage = selected && hasLoaded && !isPaging && !refreshing && !finishLine.caughtUp && pageCursor != null
        if (!canPage) return null
        isPaging = true
        publish()
        val epoch = epoch
        val job = scope.launch {
            fetchPages(first = false, epoch = epoch)
            pageJob = null
        }
        pageJob = job
        return job
    }

    private suspend fun loadFirstPage(epoch: Int) {
        if (visitMark == null) visitMark = lastVisit.beginVisit(pubkey, visitStorage)
        isLoading = true
        publish()
        loadOwnLocalPosts()
        if (epoch != this.epoch) return
        val applied = fetchPages(first = true, epoch = epoch)
        if (!applied || epoch != this.epoch) return
        isLoading = false
        loadJob = null
        if (selected) startTail(tailAnchor())
        publish()
    }

    /**
     * One page, then more while a full page added no visible row (bounded,
     * and only while the cursor moves back). Returns false when abandoned.
     * A refresh that fails or times out keeps the list as it was: only an
     * answered page (or the floor) replaces it.
     */
    private suspend fun fetchPages(first: Boolean, epoch: Int, refresh: Boolean = false): Boolean {
        var replace = first
        var hops = 0
        while (true) {
            pendingClose?.join()
            if (epoch != this.epoch) return false
            val previousCursor = if (replace) null else pageCursor
            val answer = client.feedPage(until = if (replace) null else pageCursor)
            currentCoroutineContext().ensureActive()
            if (epoch != this.epoch) return false

            val outcome = answer.outcome
            val answered = outcome == FreshOutcome.Answered || outcome == FreshOutcome.Floor
            val accepted = answer.events.filter(::accept)
            if (replace && refresh && !answered) {
                // Failed, timed-out or refused refresh: keep what is shown.
                // Posts that did arrive are already marked seen by the
                // client, so the restarted tail won't send them again: they
                // are held back instead of being lost.
                holdPending(accepted)
                persist(accepted)
                // The refresh cancelled an unfinished recipe load; a kept
                // finish line needs its row.
                if (finishLine.caughtUp && !recipesLoaded) startRecipes()
                isPaging = false
                publish()
                return true
            }
            if (replace) {
                relayPosts.clear()
                relayPosts.addAll(FreshSpacing.spaceAuthors(emptyList(), collapseEdits(accepted, replacing = false)))
                pageCursor = null
                finishLine = FreshFinishLine.NONE
                if (refresh) {
                    // The new page holds what was held back; anything newer
                    // comes again on the tail, which restarts with the overlap.
                    pendingNew.clear()
                    pillDismissedAt = 0
                }
            }
            val incoming = if (replace) {
                emptyList()
            } else {
                val shown = relayPosts.mapTo(HashSet()) { it.id }
                collapseEdits(accepted.filter { it.id !in shown }, replacing = true)
            }
            if (!replace) relayPosts.addAll(FreshSpacing.spaceAuthors(relayPosts, incoming))
            answer.events.minOfOrNull { it.created_at }?.let { pageCursor = it }
            persist(accepted)

            if (first && replace) {
                hasLoaded = true
                loadFailed = if (answered) false else answer.events.isEmpty()
            }
            val reachedEnd = when (outcome) {
                FreshOutcome.Floor -> true
                FreshOutcome.Answered -> answer.isShortPage
                else -> false
            }
            val added = if (replace) relayPosts.size else incoming.size
            replace = false
            emit()

            if (reachedEnd) {
                reachEnd()
                break
            }
            val cursor = pageCursor
            val cursorMoved = cursor != null && (previousCursor == null || cursor < previousCursor)
            hops += 1
            if (outcome != FreshOutcome.Answered || added != 0 || !cursorMoved || hops > MAX_EMPTY_PAGE_HOPS) break
        }
        isPaging = false
        publish()
        return true
    }

    private fun reachEnd() {
        finishLine = FreshFinishLine.of(reached = true)
        // Own posts older than the last page are placed once the end is
        // reached, so the list is rebuilt here.
        emit()
        if (!recipesLoaded) startRecipes()
    }

    // Recipes

    private fun startRecipes() {
        if (!selected || recipeJob != null) return
        val epoch = epoch
        recipeJob = scope.launch { loadRecipes(epoch) }
    }

    private suspend fun loadRecipes(epoch: Int) {
        pendingClose?.join()
        if (epoch != this.epoch) return
        val answer = client.recipes()
        currentCoroutineContext().ensureActive()
        if (epoch != this.epoch) return
        val rules = rules()
        val ceiling = now() + FreshRelayClient.FUTURE_SKEW_SECONDS
        val byCoordinate = HashMap<String, NostrEvent>()
        // Recipes only. The feed's rules minus the old-edit rule (recipes
        // keep old edits): reader filters, deletions, future dates.
        for (event in answer.events) {
            if (!FreshPosts.isRecipe(event.kind, event.tags)) continue
            if (!FreshPosts.passesReaderFilters(event, rules) || isDeleted(event) || event.created_at > ceiling) continue
            val coordinate = coordinate(event)
            val kept = byCoordinate[coordinate]
            if (kept != null && kept.created_at >= event.created_at) continue
            byCoordinate[coordinate] = event
        }
        // Kept beyond the row's limit so recipes excluded for being in the
        // feed leave room for others.
        recipePool = FreshRelayClient.newestFirst(byCoordinate.values.toList()).take(RECIPE_POOL_LIMIT)
        recipesLoaded = answer.outcome == FreshOutcome.Answered
        recipeJob = null
        announce(recipePool)
        publish()
    }

    // Tail

    private fun startTail(since: Long) {
        stopTail()
        val epoch = epoch
        val close = pendingClose
        tailStatus = FreshTailStatus.LIVE
        tailJob = scope.launch {
            close?.join()
            if (epoch != this@FreshFeedController.epoch || !selected) return@launch
            client.liveTail(since).collect { update ->
                if (epoch == this@FreshFeedController.epoch) handleTail(update)
            }
        }
    }

    private fun stopTail() {
        tailJob?.cancel()
        tailJob = null
        retryJob?.cancel()
        retryJob = null
        tailStatus = FreshTailStatus.IDLE
    }

    /**
     * The newest relay post, clamped to now plus the skew: a future-dated
     * post must not start a tail days ahead and miss everything until then.
     */
    private fun tailAnchor(): Long {
        val ceiling = now() + FreshRelayClient.FUTURE_SKEW_SECONDS
        return minOf(relayPosts.maxOfOrNull { it.created_at } ?: now(), ceiling)
    }

    private fun handleTail(update: FreshTailUpdate) {
        when (update) {
            is FreshTailUpdate.Event -> {
                tailStatus = FreshTailStatus.LIVE
                retryAttempt = 0
                val event = update.event
                if (accept(event)) {
                    persist(listOf(event))
                    // An edit of a long-form post already in the list takes
                    // its row at once; everything else waits for showPending.
                    // So does the relay's copy of an own post pinned already:
                    // same id, same row, nothing new to announce.
                    when {
                        event.kind == 30023 && replaceShownEdit(event) -> emit()
                        posts.any { it.id == event.id } -> {
                            insertByTime(event)
                            emit()
                        }
                        else -> holdPending(listOf(event))
                    }
                }
            }
            FreshTailUpdate.Lost -> {
                tailStatus = FreshTailStatus.LOST
                scheduleTailRetry()
            }
            is FreshTailUpdate.Revived -> tailStatus = FreshTailStatus.LIVE
            is FreshTailUpdate.Ended -> tailStatus = FreshTailStatus.ENDED
        }
        publish()
    }

    /** Hold posts back, skipping any already shown or waiting. */
    private fun holdPending(events: List<NostrEvent>) {
        for (event in events) {
            if (relayPosts.none { it.id == event.id } && pendingNew.none { it.id == event.id }) pendingNew.add(event)
        }
    }

    /**
     * The reader asked for the waiting posts: they go into the list (a new
     * post on top, a late-admitted one at its time, an edit in its row). The
     * view scrolls to the top in the same tap.
     */
    fun showPending(): Job = scope.launch {
        val waiting = pendingNew.filter(::accept)
        pendingNew.clear()
        pillDismissedAt = 0
        for (event in waiting) {
            if (event.kind == 30023 && replaceEdit(event)) continue
            insertByTime(event)
        }
        emit()
    }

    /**
     * The pill's close button: it hides until another post arrives; the
     * waiting posts stay waiting (still not inserted under the reader).
     */
    fun dismissPendingPill(): Job = scope.launch {
        pillDismissedAt = pendingNew.size
        publish()
    }

    /**
     * While the app stays in the foreground, a lost tail is revived after a
     * growing wait. Backgrounding stops it; [foregrounded] revives at once
     * instead.
     */
    private fun scheduleTailRetry() {
        if (!selected || !appActive || retryJob != null) return
        val wait = retryDelays[minOf(retryAttempt, retryDelays.size - 1)]
        retryAttempt += 1
        val epoch = epoch
        retryJob = scope.launch {
            delay(wait)
            if (epoch != this@FreshFeedController.epoch) return@launch
            retryJob = null
            if (!selected || !appActive || tailStatus != FreshTailStatus.LOST) return@launch
            client.reviveTail()
        }
    }

    // Ingest

    private fun accept(event: NostrEvent): Boolean {
        if (event.kind !in FreshPosts.FEED_KINDS) return false
        // Dated more than the app's skew ahead: dropped.
        if (event.created_at > now() + FreshRelayClient.FUTURE_SKEW_SECONDS) return false
        if (isDeleted(event)) return false
        return FreshPosts.passesFreshFilters(event, rules(), now())
    }

    /**
     * Long-form edits share a coordinate: keep the newest per coordinate
     * within the incoming page, and (when [replacing]) against what is
     * already shown, where an older row is replaced in place.
     */
    private fun collapseEdits(incoming: List<NostrEvent>, replacing: Boolean): List<NostrEvent> {
        val newest = HashMap<String, NostrEvent>()
        for (event in incoming) {
            if (event.kind != 30023) continue
            val c = coordinate(event)
            val kept = newest[c]
            if (kept != null && kept.created_at >= event.created_at) continue
            newest[c] = event
        }
        val out = ArrayList<NostrEvent>(incoming.size)
        for (event in incoming) {
            if (event.kind == 30023) {
                if (newest[coordinate(event)]?.id != event.id) continue
                if (replacing && replaceEdit(event)) continue
            }
            out.add(event)
        }
        return out
    }

    /**
     * True when [event] (kind 30023) matched a relay row's coordinate: the
     * newer of the two now sits in that row, and nothing is added.
     */
    private fun replaceEdit(event: NostrEvent): Boolean {
        val c = coordinate(event)
        val i = relayPosts.indexOfFirst { it.kind == 30023 && coordinate(it) == c }
        if (i < 0) return false
        if (relayPosts[i].created_at < event.created_at) relayPosts[i] = event
        return true
    }

    /**
     * [replaceEdit], or the same for a row that is the user's own pinned
     * copy: the relay's edit joins the relay posts at its time and [emit]
     * keeps the newer of the two.
     */
    private fun replaceShownEdit(event: NostrEvent): Boolean {
        if (replaceEdit(event)) return true
        val c = coordinate(event)
        if (posts.none { it.kind == 30023 && coordinate(it) == c }) return false
        insertByTime(event)
        return true
    }

    private fun insertByTime(event: NostrEvent) {
        if (relayPosts.any { it.id == event.id }) return
        val index = relayPosts.indexOfFirst { it.created_at < event.created_at }
        relayPosts.add(if (index < 0) relayPosts.size else index, event)
    }

    // Own posts

    /**
     * The user's own public, signed, top-level post in the window that
     * passes the filters. A private reply is cached as an unsigned synthetic
     * kind 1 (`PrivateReplyPublisher`): it fails [verify], and the private
     * mark is checked as well for the session it was sent in.
     */
    private fun isOwnCandidate(event: NostrEvent, floor: Long): Boolean =
        event.pubkey == pubkey &&
            event.kind in FreshPosts.FEED_KINDS &&
            event.created_at >= floor &&
            !isPrivate(event.id) &&
            isTopLevel(event) &&
            accept(event) &&
            verify(event)

    /**
     * Reload own posts from local storage (activation and refresh). This is
     * also how a post published while Fresh was not collecting arrives.
     */
    private suspend fun loadOwnLocalPosts() {
        val local = try {
            ownLocalPosts()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val floor = now() - FreshPosts.FREE_WINDOW_SECONDS
        for (event in local) {
            if (isOwnCandidate(event, floor)) ownPosts[event.id] = event
        }
    }

    /** Pin a post the user just published (no relay query). Shown at once, never held. */
    private fun insertOwnPublished(event: NostrEvent) {
        if (!isOwnCandidate(event, now() - FreshPosts.FREE_WINDOW_SECONDS)) return
        ownPosts[event.id] = event
        emit()
    }

    // Signals

    private fun observe() {
        scope.launch { published.collect { insertOwnPublished(it) } }
        scope.launch { rulesChanged.collect { applyRules() } }
        scope.launch { deletions.collect { dropDeleted(it) } }
    }

    /** The reader's mutes changed (a block, a muted word): what no longer passes goes at once. */
    private fun applyRules() {
        val rules = rules()
        val hidden = { event: NostrEvent -> !FreshPosts.passesReaderFilters(event, rules) }
        relayPosts.removeAll(hidden)
        pendingNew.removeAll(hidden)
        pillDismissedAt = minOf(pillDismissedAt, pendingNew.size)
        ownPosts.values.removeAll(hidden)
        recipePool = recipePool.filterNot(hidden)
        emit()
    }

    /** A deletion the app just recorded: the post goes at once, own pinned posts included. */
    private fun dropDeleted(eventId: String) {
        val gone = { event: NostrEvent -> event.id == eventId || isDeleted(event) }
        relayPosts.removeAll(gone)
        pendingNew.removeAll(gone)
        pillDismissedAt = minOf(pillDismissedAt, pendingNew.size)
        ownPosts.values.removeAll(gone)
        recipePool = recipePool.filterNot(gone)
        emit()
    }

    // Emit

    private fun emit() {
        val floor = now() - FreshPosts.FREE_WINDOW_SECONDS
        val oldestRelay = relayPosts.minOfOrNull { it.created_at }
        val merged = ArrayList(relayPosts)
        val shownIds = merged.mapTo(HashSet()) { it.id }
        val rowByCoordinate = HashMap<String, Int>()
        merged.forEachIndexed { i, event ->
            if (event.kind == 30023) rowByCoordinate[coordinate(event)] = i
        }

        // Own posts the relay didn't return, within the window, and not older
        // than what is loaded (unless the end is reached), newest first.
        // Long-form: one per coordinate, the newest of own and relay copies.
        val seenOwnCoordinates = HashSet<String>()
        val candidates = ownPosts.values
            .filter { it.id !in shownIds && it.created_at >= floor && !isDeleted(it) }
            .filter { finishLine.caughtUp || oldestRelay == null || it.created_at >= oldestRelay }
            .sortedWith(compareByDescending<NostrEvent> { it.created_at }.thenBy { it.id })
            .filter { own ->
                if (own.kind != 30023) return@filter true
                val c = coordinate(own)
                if (!seenOwnCoordinates.add(c)) return@filter false // an older own edit
                val row = rowByCoordinate[c] ?: return@filter true
                if (merged[row].created_at < own.created_at) merged[row] = own
                false
            }
        for (own in candidates) {
            val index = merged.indexOfFirst { it.created_at < own.created_at }
            merged.add(if (index < 0) merged.size else index, own)
        }
        posts = merged
        announce(merged)

        // The newest row shown is the next visit's mark (own posts and edits
        // included), clamped like the tail anchor.
        val newest = merged.maxOfOrNull { it.created_at }
        if (selected && appActive && newest != null) {
            val ceiling = now() + FreshRelayClient.FUTURE_SKEW_SECONDS
            lastVisit.recordNewest(minOf(newest, ceiling), pubkey, visitStorage)
        }
        publish()
    }

    private fun announce(events: List<NostrEvent>) {
        val fresh = events.filter { announced.add(it.id) }
        if (fresh.isNotEmpty()) onShown(fresh)
    }

    private fun publish() {
        _state.value = FreshFeedState(
            posts = posts,
            rows = FreshDivider.withDivider(posts.map(::FreshRow), visitMark),
            isLoading = isLoading,
            isPaging = isPaging,
            isRefreshing = refreshing,
            loadFailed = loadFailed,
            hasLoaded = hasLoaded,
            finishLine = finishLine,
            recipePicks = recipePicks(recipePool, posts),
            visitMark = visitMark,
            tailStatus = tailStatus,
            pendingNewCount = pendingNew.size,
            showsPendingPill = pendingNew.size > pillDismissedAt,
        )
    }
}
