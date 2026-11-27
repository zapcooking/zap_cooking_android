package cooking.zap.app.fresh

import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * [FreshFeedController] driven end to end through the real
 * [FreshRelayClient] on the scripted transport (no socket, no relay), with
 * real signed events, on virtual time.
 *
 * Port of iOS `FreshFeedViewModelTests`, case for case, then the Android
 * riders. Not ported, as not applicable on Android:
 * - `reportHidesLongFormByCoordinate` and `persistedReportedCoordinate`:
 *   Android has no report action on feed posts (see [FreshPosts]).
 * - `recipeRowSkipsTaggedArticles`: the recipe row takes
 *   [FreshPosts.isRecipe] (the tag rule) here, by ruling; the case below
 *   pins that rule instead.
 * - `wipeListHasLastVisit`: Android's account removal clears no per-account
 *   preferences file; the mark stays device-local either way.
 * - `dismissIsObserved`: state is one `StateFlow` value; the pill case
 *   reads it.
 * - `kindPlumbing` and `shipsOn` live in `FreshFeedSwitchTest`.
 * Tail cases assert after [FreshFeedController.showPending]; a long-form
 * edit of a post already in the list is the exception (it replaces at once).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshFeedControllerTest {

    // Fixtures

    private class Author {
        private val keys = Keys.generate()
        val pubkey: String = keys.pubkey.toHex()

        fun event(
            createdAt: Long,
            kind: Int = 1,
            content: String? = null,
            tags: List<List<String>> = emptyList(),
        ): NostrEvent = NostrEvent.create(
            privkey = keys.privkey, pubkey = keys.pubkey, kind = kind,
            content = content ?: "post $createdAt ${UUID.randomUUID()}",
            tags = tags, createdAt = createdAt,
        )

        fun longForm(createdAt: Long, d: String, publishedAt: Long? = null, recipe: Boolean = false): NostrEvent {
            val tags = mutableListOf(listOf("d", d))
            if (publishedAt != null) tags.add(listOf("published_at", publishedAt.toString()))
            if (recipe) tags.add(listOf("t", "zapcooking"))
            return event(createdAt, kind = 30023, content = "long form $d $createdAt", tags = tags)
        }
    }

    private class Store : FreshVisitStorage {
        val data = HashMap<String, String>()
        override fun freshVisitValue(key: String): String? = data[key]
        override fun setFreshVisitValue(key: String, value: String) {
            data[key] = value
        }
    }

    private class Req(val subId: String, val filter: JsonObject) {
        val since: Long? get() = filter["since"]?.jsonPrimitive?.long
        val until: Long? get() = filter["until"]?.jsonPrimitive?.long
        val limit: Int? get() = filter["limit"]?.jsonPrimitive?.int
        val kinds: List<Int>? get() = filter["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }
        val tTags: List<String>? get() = filter["#t"]?.jsonArray?.map { it.jsonPrimitive.content }
        val isTail: Boolean
            get() = limit == FreshRelayClient.TAIL_BACKLOG_LIMIT && kinds == FreshPosts.FEED_KINDS && until == null
        val isPage: Boolean get() = limit == FreshRelayClient.PAGE_SIZE
        val isRecipes: Boolean get() = kinds == listOf(30023)
    }

    /** One controller on its own scripted relay, with every seam a test can turn. */
    private class Rig(scope: TestScope, val pubkey: String, val store: Store, retryMs: Long) {
        val transport = FakeFreshTransport()
        var rules: FreshReaderRules? = null
        val privateIds = HashSet<String>()
        val deletedIds = HashSet<String>()
        var own: List<NostrEvent> = emptyList()
        var ownGate: CompletableDeferred<Unit>? = null
        val persisted = ArrayList<NostrEvent>()
        val shown = ArrayList<NostrEvent>()
        val published = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
        val rulesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val deletions = MutableSharedFlow<String>(extraBufferCapacity = 64)

        val controller = FreshFeedController(
            pubkey = pubkey,
            client = FreshRelayClient(
                transport = transport, now = { NOW }, silenceTimeoutMs = 5_000,
                dispatcher = StandardTestDispatcher(scope.testScheduler),
                monotonicMs = { scope.testScheduler.currentTime },
            ),
            now = { NOW },
            rules = { rules },
            isPrivate = { it in privateIds },
            isDeleted = { it.id in deletedIds },
            ownLocalPosts = {
                ownGate?.await()
                own
            },
            persist = { persisted.addAll(it) },
            onShown = { shown.addAll(it) },
            visitStorage = store,
            lastVisit = FreshLastVisit(),
            published = published,
            rulesChanged = rulesChanged,
            deletions = deletions,
            retryDelaysMs = listOf(retryMs),
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        val state: FreshFeedState get() = controller.state.value
        val postIds: List<String> get() = state.posts.map { it.id }
        val socket: FakeFreshSocket? get() = transport.latest
        val allSent: List<String> get() = transport.opened.flatMap { it.sent }

        fun reqs(socket: FakeFreshSocket? = this.socket): List<Req> = (socket?.sent ?: emptyList()).mapNotNull { frame ->
            val array = Json.parseToJsonElement(frame) as? JsonArray ?: return@mapNotNull null
            if (array.size != 3 || array[0].jsonPrimitive.content != "REQ") return@mapNotNull null
            Req(array[1].jsonPrimitive.content, array[2].jsonObject)
        }

        fun answer(req: Req, events: List<NostrEvent>, eose: Boolean = true) {
            for (event in events) socket?.feed("""["EVENT","${req.subId}",${event.toJson()}]""")
            if (eose) socket?.feed("""["EOSE","${req.subId}"]""")
        }

        fun closed(req: Req, reason: String) {
            socket?.feed("""["CLOSED","${req.subId}","$reason"]""")
        }
    }

    private fun TestScope.makeRig(
        pubkey: String = "c".repeat(64),
        store: Store = Store(),
        retryMs: Long = 30,
        setup: Rig.() -> Unit = {},
    ): Rig = Rig(this, pubkey, store, retryMs).apply(setup)

    /** Activate and answer page 1 with [page]; returns once the tail's REQ is out. */
    private fun TestScope.start(rig: Rig, page: List<NostrEvent>): Pair<Req, Req> {
        rig.controller.activate()
        runCurrent()
        val pageReq = rig.reqs().first { it.isPage }
        rig.answer(pageReq, page)
        runCurrent()
        // A short page 1 also asks for the recipe row, in either order with
        // the tail: find the tail by its filter, not its position.
        val tail = rig.reqs().firstOrNull { it.isTail }
        assertTrue("tail REQ never sent", tail != null)
        return pageReq to tail!!
    }

    /** Tail posts wait; show them (what the view does on a tap). */
    private fun TestScope.reveal(rig: Rig, count: Int) {
        runCurrent()
        assertEquals("$count tail post(s) should be waiting", count, rig.state.pendingNewCount)
        rig.controller.showPending()
        runCurrent()
    }

    private fun TestScope.refreshPageReq(rig: Rig, nth: Int): Req {
        rig.controller.refresh()
        runCurrent()
        val pages = rig.reqs().filter { it.isPage }
        assertEquals("page REQ #$nth on this socket", nth, pages.size)
        return pages.last()
    }

    private fun blocking(vararg pubkeys: String, words: Set<String> = emptySet(), hashtags: Set<String> = emptySet()) =
        FreshReaderRules(
            isUserBlocked = { it in pubkeys },
            containsMutedWord = { content -> words.any { content.contains(it, ignoreCase = true) } },
            mutedHashtags = hashtags,
        )

    private companion object {
        const val NOW = 1_800_000_000L
        const val FLOOR = NOW - FreshPosts.FREE_WINDOW_SECONDS
        const val DAY = 86_400L
        const val OVERLAP = FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS

        val mainDir: File = sequenceOf("src/main/kotlin/cooking/zap/app", "app/src/main/kotlin/cooking/zap/app")
            .map(::File).first { it.isDirectory }
    }

    // Page 1 and filters

    /** page 1: since = floor, kinds [1,30023,1068]; mutes, blocks, muted hashtags and old edits dropped */
    @Test
    fun firstPageFilters() = runTest {
        val a = Author()
        val muted = Author()
        val blocked = Author()
        val rig = makeRig { rules = blocking(blocked.pubkey, words = setOf("spoiler"), hashtags = setOf("offal")) }
        val keep1 = a.event(NOW - 100)
        val keep2 = muted.event(NOW - 200)
        val page = listOf(
            keep1, keep2,
            muted.event(NOW - 250, content = "big SPOILER ahead"),
            blocked.event(NOW - 300),
            a.event(NOW - 320, tags = listOf(listOf("t", "Offal"))),
            a.longForm(NOW - 350, d = "old", publishedAt = NOW - 400 * DAY),
        )
        val (pageReq, tailReq) = start(rig, page)

        assertEquals(FLOOR, pageReq.since)
        assertEquals(listOf(1, 30023, 1068), pageReq.kinds)
        assertNull(pageReq.until)
        assertEquals(listOf(keep1.id, keep2.id), rig.postIds)
        assertTrue(rig.state.hasLoaded && !rig.state.loadFailed && !rig.state.isLoading)
        assertEquals(listOf(1, 30023, 1068), tailReq.kinds)
        assertEquals(FreshTailStatus.LIVE, rig.state.tailStatus)
        assertFalse(rig.allSent.any { it.startsWith("[\"AUTH\"") || it.startsWith("[\"EVENT\"") })
    }

    /** page 1 failing with nothing to show is loadFailed; a refresh that answers clears it */
    @Test
    fun firstPageFailure() = runTest {
        val rig = makeRig()
        rig.controller.activate()
        runCurrent()
        rig.closed(rig.reqs().first(), "error: shutting down")
        runCurrent()
        assertTrue(rig.state.loadFailed && rig.state.showsFailureMessage)
        assertTrue(rig.state.posts.isEmpty())

        val post = Author().event(NOW - 10)
        val req2 = refreshPageReq(rig, nth = 2)
        assertEquals(FLOOR, req2.since)
        assertNull(req2.until)
        rig.answer(req2, listOf(post))
        runCurrent()
        assertFalse(rig.state.loadFailed)
        assertEquals(listOf(post.id), rig.postIds)
        assertFalse(rig.state.isRefreshing)
    }

    // Paging and the finish line

    /** paging asks until = the previous page's oldest; a short page reaches the end and loads the recipe row */
    @Test
    fun pagingToTheEnd() = runTest {
        val a = Author()
        val b = Author()
        val rig = makeRig()
        val page1 = (0 until 30).map { i -> (if (i % 2 == 0) a else b).event(NOW - 10 - i * 10) }
        start(rig, page1)
        assertEquals(30, rig.state.posts.size)
        assertEquals(FreshFinishLine.NONE, rig.state.finishLine)

        rig.controller.loadMore()
        runCurrent()
        assertTrue(rig.state.isPaging)
        val pageReq = rig.reqs()[2]
        assertEquals(page1.minOf { it.created_at }, pageReq.until)
        assertEquals(FLOOR, pageReq.since)
        rig.answer(pageReq, listOf(a.event(NOW - 400), b.event(NOW - 410)))
        runCurrent()
        assertEquals(32, rig.state.posts.size)
        assertTrue(rig.state.finishLine.caughtUp && rig.state.finishLine.recipeRow)
        assertFalse(rig.state.isPaging)

        // The recipe row: kind 30023 with #t, no since; newest per
        // coordinate, old edits kept, reader's rules applied.
        val recipesReq = rig.reqs()[3]
        assertEquals(listOf(30023), recipesReq.kinds)
        assertNull(recipesReq.since)
        assertEquals(setOf("zapcooking", "nostrcooking"), recipesReq.tTags?.toSet())
        val old = a.longForm(NOW - 900 * DAY, d = "soup", publishedAt = NOW - 1000 * DAY, recipe = true)
        val newer = a.longForm(NOW - 800 * DAY, d = "soup", publishedAt = NOW - 1000 * DAY, recipe = true)
        val other = b.longForm(NOW - 700 * DAY, d = "bread", recipe = true)
        rig.answer(recipesReq, listOf(old, newer, other))
        runCurrent()
        assertEquals(listOf(other.id, newer.id), rig.state.recipePicks.map { it.id })
    }

    /** the recipe row takes recipes only (FreshPosts.isRecipe): long-form without a recipe tag never appears */
    @Test
    fun recipeRowTakesRecipesOnly() = runTest {
        val a = Author()
        val rig = makeRig()
        start(rig, emptyList())
        val recipesReq = rig.reqs().first { it.isRecipes }
        val recipe = a.longForm(NOW - 30 * DAY, d = "pie", recipe = true)
        val upperTag = a.event(NOW - 31 * DAY, kind = 30023, tags = listOf(listOf("d", "tart"), listOf("t", "NostrCooking")))
        // A relay that ignored the #t filter: an untagged article.
        val article = a.longForm(NOW - 20 * DAY, d = "essay")
        rig.answer(recipesReq, listOf(article, recipe, upperTag))
        runCurrent()
        assertEquals(listOf(recipe.id, upperTag.id), rig.state.recipePicks.map { it.id })
    }

    /** the recipe pool keeps 60 and the row shows 12 */
    @Test
    fun recipePoolAndRowLimits() = runTest {
        val a = Author()
        val rig = makeRig()
        val inFeed = (0 until 3).map { a.longForm(NOW - 100 - it, d = "r$it", recipe = true) }
        start(rig, inFeed)
        val recipesReq = rig.reqs().first { it.isRecipes }
        val recipes = (0 until 70).map { a.longForm(NOW - 30 * DAY - it, d = "r$it", recipe = true) }
        rig.answer(recipesReq, recipes)
        runCurrent()
        // The three newest are in the feed by coordinate: the row moves on to the next twelve.
        assertEquals(recipes.subList(3, 15).map { it.id }, rig.state.recipePicks.map { it.id })
        assertEquals(12, FreshFeedController.RECIPE_ROW_LIMIT)
        assertEquals(60, FreshFeedController.RECIPE_POOL_LIMIT)
        // Beyond the pool's 60, nothing: with 58 of the pool in the feed, 2 are left.
        assertEquals(
            recipes.subList(58, 60).map { it.id },
            FreshFeedController.recipePicks(recipes.take(60), recipes.take(58)).map { it.id },
        )
    }

    /** auth-required on a page is the floor: the end, not an error */
    @Test
    fun floorEndsTheFeed() = runTest {
        val a = Author()
        val rig = makeRig()
        start(rig, (0 until 30).map { a.event(NOW - 10 - it * 10) })
        rig.controller.loadMore()
        runCurrent()
        val req = rig.reqs()[2]
        rig.socket?.feed("""["AUTH","challenge"]""")
        rig.closed(req, "auth-required: posts older than 14 days are for Zap Cooking members")
        runCurrent()
        assertTrue(rig.state.finishLine.caughtUp)
        assertFalse(rig.state.loadFailed)
        assertEquals(30, rig.state.posts.size)
        assertFalse(rig.allSent.any { it.startsWith("[\"AUTH\"") })
    }

    // Block: instant hide

    /** block: the author's posts go at once, and the tail can't bring them back */
    @Test
    fun blockInstantHide() = runTest {
        val x = Author()
        val y = Author()
        val rig = makeRig()
        val xPost = x.event(NOW - 100)
        val yPost = y.event(NOW - 200)
        val (_, tail) = start(rig, listOf(xPost, yPost))
        assertEquals(2, rig.state.posts.size)

        // What SocialActionManager.blockUser does: the mute list changes.
        rig.rules = blocking(x.pubkey)
        rig.rulesChanged.tryEmit(Unit)
        runCurrent()
        assertEquals(listOf(yPost.id), rig.postIds)

        val yLater = y.event(NOW - 4)
        rig.answer(tail, listOf(x.event(NOW - 5), yLater), eose = false)
        reveal(rig, count = 1)
        assertEquals(yLater.id, rig.postIds.first())
        assertFalse(rig.state.posts.any { it.pubkey == x.pubkey })
    }

    /** a blocked author's waiting posts leave the pill too */
    @Test
    fun hideClearsWaitingPosts() = runTest {
        val x = Author()
        val y = Author()
        val rig = makeRig()
        val (_, tail) = start(rig, listOf(y.event(NOW - 100)))
        rig.answer(tail, listOf(x.event(NOW - 10), y.event(NOW - 9)), eose = false)
        runCurrent()
        assertEquals(2, rig.state.pendingNewCount)
        rig.rules = blocking(x.pubkey)
        rig.rulesChanged.tryEmit(Unit)
        runCurrent()
        assertEquals(1, rig.state.pendingNewCount)
    }

    // Long-form edits

    /** long-form edits collapse to the newest per coordinate on the page; a tail edit of a shown post replaces it at once */
    @Test
    fun longFormEditsCollapse() = runTest {
        val z = Author()
        val other = Author()
        val rig = makeRig()
        val v1 = z.longForm(NOW - 300, d = "x")
        val v2 = z.longForm(NOW - 200, d = "x")
        val note = other.event(NOW - 250)
        val (_, tail) = start(rig, listOf(v2, note, v1))
        assertEquals(listOf(v2.id, note.id), rig.postIds)

        val v3 = z.longForm(NOW - 100, d = "x")
        rig.answer(tail, listOf(v3), eose = false)
        runCurrent()
        // In place, without showPending, and nothing waits.
        assertEquals(listOf(v3.id, note.id), rig.postIds)
        assertEquals(0, rig.state.pendingNewCount)
        assertFalse(rig.state.showsPendingPill)

        // An older edit arriving late changes nothing.
        rig.answer(tail, listOf(z.longForm(NOW - 150, d = "x")), eose = false)
        runCurrent()
        assertEquals(listOf(v3.id, note.id), rig.postIds)
        assertEquals(0, rig.state.pendingNewCount)
    }

    /** a long-form post new to the list is held like any tail post; its edit while held shows once */
    @Test
    fun newLongFormFromTheTailIsHeld() = runTest {
        val z = Author()
        val rig = makeRig()
        val note = z.event(NOW - 300)
        val (_, tail) = start(rig, listOf(note))
        val v1 = z.longForm(NOW - 50, d = "new")
        val v2 = z.longForm(NOW - 40, d = "new")
        rig.answer(tail, listOf(v1, v2), eose = false)
        runCurrent()
        assertEquals(listOf(note.id), rig.postIds)
        reveal(rig, count = 2)
        assertEquals(listOf(v2.id, note.id), rig.postIds)
    }

    /** a long-form edit from the tail is persisted as well as shown */
    @Test
    fun tailEditPersisted() = runTest {
        val z = Author()
        val rig = makeRig()
        val (_, tail) = start(rig, listOf(z.longForm(NOW - 300, d = "x")))
        val edit = z.longForm(NOW - 100, d = "x")
        rig.answer(tail, listOf(edit), eose = false)
        runCurrent()
        assertEquals(listOf(edit.id), rig.postIds)
        assertTrue(rig.persisted.any { it.id == edit.id })
    }

    // Own posts

    /** own posts: last 14 days from local sources, in time order; a fresh publish is inserted at once; the relay's copy is one row */
    @Test
    fun ownPostsPinned() = runTest {
        val me = Author()
        val other = Author()
        val mine = me.event(NOW - 150)
        val rig = makeRig(pubkey = me.pubkey) {
            own = listOf(mine, me.event(NOW - 20 * DAY), other.event(NOW - 10))
        }
        val p1 = other.event(NOW - 100)
        val p2 = other.event(NOW - 200)
        val (_, tail) = start(rig, listOf(p1, p2))
        assertEquals(listOf(p1.id, mine.id, p2.id), rig.postIds)

        // A fresh publish (EventRepository.publishedEvents) is pinned at its
        // time, at once: never held behind the pending count.
        val published = me.event(NOW - 5)
        rig.published.tryEmit(published)
        runCurrent()
        assertEquals(listOf(published.id, p1.id, mine.id, p2.id), rig.postIds)
        assertEquals(0, rig.state.pendingNewCount)

        // The relay later returns it: one copy, and nothing new to announce.
        rig.answer(tail, listOf(published), eose = false)
        runCurrent()
        assertEquals(0, rig.state.pendingNewCount)
        assertEquals(listOf(published.id, p1.id, mine.id, p2.id), rig.postIds)
    }

    /** private replies never show: a cached one marked private, an unsigned synthetic, and either arriving as a publish */
    @Test
    fun privateRepliesExcluded() = runTest {
        val me = Author()
        val other = Author()
        val signed = me.event(NOW - 150)
        val cachedPrivate = me.event(NOW - 120)
        val unsigned = me.event(NOW - 110, content = "private reply").copy(sig = "")
        val rig = makeRig(pubkey = me.pubkey) {
            privateIds.add(cachedPrivate.id)
            own = listOf(signed, cachedPrivate, unsigned)
        }
        val p = other.event(NOW - 300)
        start(rig, listOf(p))
        assertEquals(listOf(signed.id, p.id), rig.postIds)

        rig.published.tryEmit(me.event(NOW - 5, content = "private").copy(sig = ""))
        val flagged = me.event(NOW - 4)
        rig.privateIds.add(flagged.id)
        rig.published.tryEmit(flagged)
        runCurrent()
        assertEquals(listOf(signed.id, p.id), rig.postIds)
    }

    /**
     * Rider d. An own post must pass [FreshEventVerifier] and must not be
     * private. After a restart the private marks are gone (they live in
     * memory), and the store still holds the synthetic unsigned copy of a
     * private reply: it is excluded by the signature check alone. So is a
     * stored event whose content no longer matches its id.
     */
    @Test
    fun ownPostMustVerifyAfterARestartToo() = runTest {
        val me = Author()
        val real = me.event(NOW - 150)
        val synthetic = me.event(NOW - 120, content = "a private reply").copy(sig = "")
        val tampered = me.event(NOW - 110).copy(content = "edited after signing")
        val wrongSig = me.event(NOW - 100).copy(sig = me.event(NOW - 99).sig)
        assertFalse(FreshEventVerifier.isValid(synthetic))
        assertFalse(FreshEventVerifier.isValid(tampered))
        assertFalse(FreshEventVerifier.isValid(wrongSig))
        // No private marks at all: a fresh process.
        val rig = makeRig(pubkey = me.pubkey) { own = listOf(real, synthetic, tampered, wrongSig) }
        start(rig, emptyList())
        assertEquals(listOf(real.id), rig.postIds)

        // The same on a refresh, and for the publish signal.
        rig.own = listOf(real, synthetic)
        val pageReq = refreshPageReq(rig, nth = 2)
        rig.answer(pageReq, emptyList())
        rig.published.tryEmit(synthetic)
        runCurrent()
        assertEquals(listOf(real.id), rig.postIds)
    }

    /**
     * Rider c. Fresh loads for a READ_ONLY account: the controller is given
     * a pubkey and nothing that can sign, and own-post pinning works from
     * the pubkey alone. Neither the controller nor its live wiring names a
     * signer, a signing mode or a key.
     */
    @Test
    fun loadsForAReadOnlyAccountWithOnlyAPubkey() = runTest {
        val me = Author()
        val other = Author()
        val mine = me.event(NOW - 150)
        // Exactly what a read-only account has: the pubkey, and posts
        // already on the device (published from another client).
        val rig = makeRig(pubkey = me.pubkey) { own = listOf(mine) }
        val p = other.event(NOW - 100)
        val (pageReq, _) = start(rig, listOf(p))
        assertEquals(FLOOR, pageReq.since)
        assertEquals(listOf(p.id, mine.id), rig.postIds)
        assertFalse(rig.allSent.any { !it.startsWith("[\"REQ\"") && !it.startsWith("[\"CLOSE\"") })

        for (name in listOf("fresh/FreshFeedController.kt", "fresh/FreshFeedWiring.kt")) {
            val source = File(mainDir, name).readText()
            for (word in listOf("NostrSigner", "LocalSigner", "RemoteSigner", "SigningMode", "signEvent", "getKeypair", "privkey", "KeyRepository")) {
                assertFalse("$name must not need $word", source.contains(word))
            }
        }
        // The view model makes the controller from the pubkey, without asking for the signer.
        val vm = File(mainDir, "viewmodel/FeedViewModel.kt").readText()
        val make = vm.substring(vm.indexOf("private fun freshForCurrentAccount()"), vm.indexOf("/** Fresh: put the posts"))
        assertTrue(make.contains("getUserPubkey()"))
        assertFalse(make.contains("signer"))
    }

    /** own posts: replies are never pinned; top-level notes, long-form and polls are */
    @Test
    fun ownRepliesNotPinned() = runTest {
        val me = Author()
        val other = Author()
        val topLevel = me.event(NOW - 150)
        val reply = me.event(NOW - 140, tags = listOf(listOf("e", "a".repeat(64), "", "root")))
        val legacyReply = me.event(NOW - 130, tags = listOf(listOf("e", "b".repeat(64))))
        val mentionOnly = me.event(NOW - 120, tags = listOf(listOf("e", "c".repeat(64), "", "mention")))
        val poll = me.event(NOW - 110, kind = 1068)
        val rig = makeRig(pubkey = me.pubkey) { own = listOf(topLevel, reply, legacyReply, mentionOnly, poll) }
        val p = other.event(NOW - 300)
        start(rig, listOf(p))
        assertEquals(listOf(poll.id, mentionOnly.id, topLevel.id, p.id), rig.postIds)
        // A reply published now is not pinned either.
        val published = me.event(NOW - 5, tags = listOf(listOf("e", p.id, "", "reply")))
        rig.published.tryEmit(published)
        runCurrent()
        assertFalse(rig.state.posts.any { it.id == published.id })
    }

    /** own long-form keeps one row per coordinate: own edits collapse, and the newer of own and relay wins */
    @Test
    fun ownLongFormByCoordinate() = runTest {
        val me = Author()
        val ownOld = me.longForm(NOW - 500, d = "a")
        val ownNew = me.longForm(NOW - 400, d = "a")
        val ownNewerThanRelay = me.longForm(NOW - 50, d = "b")
        val rig = makeRig(pubkey = me.pubkey) { own = listOf(ownOld, ownNew, ownNewerThanRelay) }
        val relayB = me.longForm(NOW - 200, d = "b")
        val relayNote = me.event(NOW - 600)
        start(rig, listOf(relayB, relayNote))
        assertEquals(listOf(ownNewerThanRelay.id, ownNew.id, relayNote.id), rig.postIds)
    }

    /** a relay edit of a recipe shown only as the user's own pinned copy replaces it at once */
    @Test
    fun tailEditOfAnOwnPinnedLongForm() = runTest {
        val me = Author()
        val other = Author()
        val mine = me.longForm(NOW - 400, d = "stew")
        val rig = makeRig(pubkey = me.pubkey) { own = listOf(mine) }
        val p = other.event(NOW - 500)
        val (_, tail) = start(rig, listOf(p))
        assertEquals(listOf(mine.id, p.id), rig.postIds)
        val edited = me.longForm(NOW - 60, d = "stew")
        rig.answer(tail, listOf(edited), eose = false)
        runCurrent()
        assertEquals(listOf(edited.id, p.id), rig.postIds)
        assertEquals(0, rig.state.pendingNewCount)
    }

    /** a recipe published while Fresh was not collecting (persisted, no signal seen) appears on refresh */
    @Test
    fun ownRecipeArrivesOnRefresh() = runTest {
        val me = Author()
        val other = Author()
        val rig = makeRig(pubkey = me.pubkey)
        val p = other.event(NOW - 100)
        start(rig, listOf(p))
        val recipe = me.longForm(NOW - 30, d = "stew", recipe = true)
        rig.own = listOf(recipe)
        val pageReq = refreshPageReq(rig, nth = 2)
        rig.answer(pageReq, listOf(p))
        runCurrent()
        assertEquals(listOf(recipe.id, p.id), rig.postIds)
    }

    // Tail

    /** tail: a new post goes on top; a late-admitted one is placed by its time */
    @Test
    fun tailPlacement() = runTest {
        val a = Author()
        val b = Author()
        val rig = makeRig()
        val p1 = a.event(NOW - 100)
        val p2 = b.event(NOW - 1000)
        val (_, tail) = start(rig, listOf(p1, p2))
        val newest = b.event(NOW - 10)
        val late = b.event(NOW - 500)
        rig.answer(tail, listOf(newest, late), eose = false)
        reveal(rig, count = 2)
        assertEquals(listOf(newest.id, p1.id, late.id, p2.id), rig.postIds)
    }

    /** pill: tail posts wait and the list does not move; showPending inserts them by time */
    @Test
    fun pillHoldsTailPosts() = runTest {
        val a = Author()
        val b = Author()
        val rig = makeRig()
        val p1 = a.event(NOW - 100)
        val p2 = b.event(NOW - 1000)
        val (_, tail) = start(rig, listOf(p1, p2))
        val newest = b.event(NOW - 10)
        val late = b.event(NOW - 500)
        rig.answer(tail, listOf(newest, late, newest), eose = false) // a repeat counts once
        runCurrent()
        assertEquals(2, rig.state.pendingNewCount)
        assertTrue(rig.state.showsPendingPill)
        assertEquals(listOf(p1.id, p2.id), rig.postIds)

        // Close: the pill hides, the posts keep waiting, the list stays.
        rig.controller.dismissPendingPill()
        runCurrent()
        assertFalse(rig.state.showsPendingPill)
        assertEquals(2, rig.state.pendingNewCount)
        assertEquals(listOf(p1.id, p2.id), rig.postIds)
        // Another arrival brings the pill back.
        val third = a.event(NOW - 5)
        rig.answer(tail, listOf(third), eose = false)
        runCurrent()
        assertTrue(rig.state.showsPendingPill)
        assertEquals(3, rig.state.pendingNewCount)

        rig.controller.showPending()
        runCurrent()
        assertEquals(listOf(third.id, newest.id, p1.id, late.id, p2.id), rig.postIds)
        assertFalse(rig.state.showsPendingPill)
        assertEquals(0, rig.state.pendingNewCount)
        assertTrue(rig.persisted.any { it.id == late.id })
    }

    /** tail lost in the foreground is retried after the backoff; not while backgrounded; the foreground hook revives */
    @Test
    fun tailRetryWithBackoff() = runTest {
        val a = Author()
        val rig = makeRig(retryMs = 40)
        start(rig, listOf(a.event(NOW - 100)))

        rig.socket?.drop()
        runCurrent()
        assertEquals(FreshTailStatus.LOST, rig.state.tailStatus)
        assertEquals(1, rig.transport.opened.size)
        advanceTimeBy(41)
        runCurrent()
        assertEquals(2, rig.transport.opened.size)
        assertTrue(rig.reqs().single().isTail)
        assertEquals(FreshTailStatus.LIVE, rig.state.tailStatus)

        // Backgrounded: a drop is not retried.
        rig.controller.backgrounded()
        runCurrent()
        rig.socket?.drop()
        runCurrent()
        assertEquals(FreshTailStatus.LOST, rig.state.tailStatus)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, rig.transport.opened.size)

        // Foreground: revived at once, by one subscription.
        rig.controller.foregrounded()
        runCurrent()
        assertEquals(3, rig.transport.opened.size)
        val resumed = rig.reqs().single()
        assertEquals(NOW - 100 - OVERLAP, resumed.since)
        assertEquals(FreshTailStatus.LIVE, rig.state.tailStatus)
    }

    /** leaving Fresh closes the socket and keeps the list; coming back starts a tail, not page 1 */
    @Test
    fun deactivateAndReturn() = runTest {
        val a = Author()
        val rig = makeRig()
        val post = a.event(NOW - 100)
        start(rig, listOf(post))
        val first = rig.socket!!

        rig.controller.deactivate()
        runCurrent()
        assertTrue(first.cancelled)
        assertEquals(FreshTailStatus.IDLE, rig.state.tailStatus)
        assertEquals(listOf(post.id), rig.postIds)

        rig.controller.activate()
        runCurrent()
        assertEquals(2, rig.transport.opened.size)
        // A tail, and (page 1 was short, recipes unanswered) the recipe row
        // again; never page 1.
        val after = rig.reqs()
        assertFalse(after.any { it.isPage })
        assertEquals(1, after.count { it.isTail })
        assertEquals(NOW - 100 - OVERLAP, after.first { it.isTail }.since)
        assertEquals(1, after.count { it.isRecipes })
    }

    /** leaving Fresh while page 1 is still loading sends nothing; coming back loads page 1 */
    @Test
    fun deactivateDuringFirstLoad() = runTest {
        val a = Author()
        val gate = CompletableDeferred<Unit>()
        val rig = makeRig { ownGate = gate }
        rig.controller.activate()
        runCurrent()
        assertTrue(rig.state.isLoading)
        rig.controller.deactivate()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(rig.allSent.isEmpty())
        assertFalse(rig.state.hasLoaded || rig.state.isLoading)

        val post = a.event(NOW - 10)
        start(rig, listOf(post))
        assertEquals(listOf(post.id), rig.postIds)
    }

    /** leaving Fresh while a page is in flight drops its answer; the page's subscription is closed with the socket */
    @Test
    fun deactivateDuringAPage() = runTest {
        val a = Author()
        val rig = makeRig()
        val page1 = (0 until 30).map { a.event(NOW - 10 - it * 10) }
        start(rig, page1)
        rig.controller.loadMore()
        runCurrent()
        val pageReq = rig.reqs()[2]
        val socket = rig.socket!!
        rig.controller.deactivate()
        runCurrent()
        assertTrue(socket.cancelled)
        rig.answer(pageReq, listOf(a.event(NOW - 900)))
        runCurrent()
        assertEquals(30, rig.state.posts.size)
        assertFalse(rig.state.isPaging)
    }

    /** foreground is forwarded while another feed is selected, so retries work after Fresh is picked */
    @Test
    fun foregroundForwardedWhileInactive() = runTest {
        val a = Author()
        val rig = makeRig(retryMs = 30)
        rig.controller.backgrounded()
        rig.controller.foregrounded() // Fresh not selected yet
        runCurrent()
        assertTrue(rig.transport.opened.isEmpty())
        start(rig, listOf(a.event(NOW - 100)))
        rig.socket?.drop()
        runCurrent()
        advanceTimeBy(31)
        runCurrent()
        assertEquals(2, rig.transport.opened.size)
        assertTrue(rig.reqs().single().isTail)
    }

    /** coming to the foreground while page 1 is pending starts no tail before the page */
    @Test
    fun foregroundDuringFirstPage() = runTest {
        val a = Author()
        val rig = makeRig()
        rig.controller.activate()
        runCurrent()
        val pageReq = rig.reqs().single()
        rig.controller.foregrounded()
        runCurrent()
        assertEquals(1, rig.reqs().size)
        rig.answer(pageReq, listOf(a.event(NOW - 10)))
        runCurrent()
        assertEquals(1, rig.reqs().count { it.isTail })
    }

    /** refresh stops the tail before resetting seen, reloads page 1, then restarts the tail with the overlap */
    @Test
    fun refreshOrdersTheTail() = runTest {
        val a = Author()
        val rig = makeRig()
        val first = a.event(NOW - 100)
        val (_, tail) = start(rig, listOf(first))

        val pageReq = refreshPageReq(rig, nth = 2)
        assertTrue(rig.state.isRefreshing)
        val sent = rig.socket!!.sent
        val closeIndex = sent.indexOf("""["CLOSE","${tail.subId}"]""")
        val pageIndex = sent.indexOfFirst { it.contains(pageReq.subId) }
        assertTrue("the tail is closed before the page is asked", closeIndex in 0 until pageIndex)
        // The page returns the post the old tail could have delivered: it is
        // not lost to the seen-set.
        val during = a.event(NOW - 20)
        rig.answer(pageReq, listOf(during, first))
        runCurrent()
        assertEquals(listOf(during.id, first.id), rig.postIds)
        assertFalse(rig.state.isRefreshing)
        val newTail = rig.reqs().last { it.isTail }
        assertTrue(newTail.subId != tail.subId)
        assertEquals(NOW - 20 - OVERLAP, newTail.since)
    }

    /** a failed or timed-out pull-to-refresh keeps the list (and the finish line) */
    @Test
    fun failedRefreshKeepsTheList() = runTest {
        val a = Author()
        val rig = makeRig()
        val shown = listOf(a.event(NOW - 100), a.event(NOW - 200))
        start(rig, shown)
        assertTrue(rig.state.finishLine.caughtUp)

        val pageReq = refreshPageReq(rig, nth = 2)
        rig.closed(pageReq, "error: overloaded")
        runCurrent()
        assertEquals(shown.map { it.id }, rig.postIds)
        assertTrue(rig.state.finishLine.caughtUp)
        assertFalse(rig.state.loadFailed || rig.state.isRefreshing)

        // A dropped connection during the refresh: same.
        refreshPageReq(rig, nth = 3)
        rig.socket?.drop()
        runCurrent()
        assertEquals(shown.map { it.id }, rig.postIds)
        assertFalse(rig.state.isRefreshing)
    }

    /** a refresh that fails after some posts arrived keeps the list and holds those posts */
    @Test
    fun partialFailedRefreshIsHeld() = runTest {
        val a = Author()
        val rig = makeRig()
        val shown = listOf(a.event(NOW - 100))
        start(rig, shown)
        val pageReq = refreshPageReq(rig, nth = 2)
        val arrived = a.event(NOW - 10)
        rig.answer(pageReq, listOf(arrived), eose = false)
        runCurrent()
        rig.closed(pageReq, "error: overloaded")
        runCurrent()
        assertEquals(shown.map { it.id }, rig.postIds)
        assertEquals(1, rig.state.pendingNewCount)
        rig.controller.showPending()
        runCurrent()
        assertEquals(listOf(arrived.id) + shown.map { it.id }, rig.postIds)
    }

    /** a failed refresh that keeps the finish line restarts an unfinished recipe load */
    @Test
    fun failedRefreshRestartsRecipes() = runTest {
        val a = Author()
        val rig = makeRig()
        start(rig, listOf(a.event(NOW - 100))) // short page: end + recipes asked
        assertEquals(1, rig.reqs().count { it.isRecipes })
        val pageReq = refreshPageReq(rig, nth = 2)
        rig.closed(pageReq, "error: overloaded")
        runCurrent()
        assertTrue(rig.state.finishLine.caughtUp)
        val recipeReqs = rig.reqs().filter { it.isRecipes }
        assertEquals("the recipe row is asked for again", 2, recipeReqs.size)
        val recipe = a.longForm(NOW - 40 * DAY, d = "soup", recipe = true)
        rig.answer(recipeReqs.last(), listOf(recipe))
        runCurrent()
        assertEquals(listOf(recipe.id), rig.state.recipePicks.map { it.id })
    }

    /** a full page that is all filtered out fetches the next page without a scroll */
    @Test
    fun emptyFullPageHops() = runTest {
        val muted = Author()
        val a = Author()
        val rig = makeRig { rules = blocking(muted.pubkey) }
        val hiddenPage = (0 until 30).map { muted.event(NOW - 10 - it * 10) }
        rig.controller.activate()
        runCurrent()
        rig.answer(rig.reqs().single(), hiddenPage)
        runCurrent()
        val req2 = rig.reqs().first { it.until != null }
        assertEquals(hiddenPage.minOf { it.created_at }, req2.until)
        val visible = a.event(NOW - 400)
        rig.answer(req2, listOf(visible))
        runCurrent()
        assertEquals(listOf(visible.id), rig.postIds)
    }

    /** a post dated days ahead is dropped, so it can't anchor the tail; the anchor stays clamped to now + 30 s */
    @Test
    fun futureDatedAnchorClamped() = runTest {
        val a = Author()
        val rig = makeRig()
        val (_, tail) = start(rig, listOf(a.event(NOW + DAY), a.event(NOW - 100)))
        assertEquals(NOW - 100, tail.since)
        rig.controller.deactivate()
        runCurrent()
        rig.controller.activate()
        runCurrent()
        assertEquals(NOW - 100 - OVERLAP, rig.reqs().first { it.isTail }.since)

        // Within the skew, a post is kept and anchors at most now + 30 s.
        val rig2 = makeRig()
        val (_, tail2) = start(rig2, listOf(a.event(NOW + 30)))
        assertEquals(NOW + FreshRelayClient.FUTURE_SKEW_SECONDS, tail2.since)
    }

    /** posts dated more than 30 s ahead are dropped; within the skew they stay */
    @Test
    fun futureDatedDropped() = runTest {
        val a = Author()
        val rig = makeRig()
        val within = a.event(NOW + 30)
        val past = a.event(NOW - 10)
        val (_, tail) = start(rig, listOf(a.event(NOW + 31), within, past))
        assertEquals(listOf(within.id, past.id), rig.postIds)
        rig.answer(tail, listOf(a.event(NOW + 3600)), eose = false)
        runCurrent()
        assertEquals(0, rig.state.pendingNewCount)
    }

    /** page 1 failed: posts arriving on the tail show the list with the pill, not the error message */
    @Test
    fun failedFirstPageThenTailShowsPill() = runTest {
        val a = Author()
        val rig = makeRig()
        rig.controller.activate()
        runCurrent()
        rig.closed(rig.reqs().single(), "error: down")
        runCurrent()
        assertTrue(rig.state.showsFailureMessage)
        val tail = rig.reqs().first { it.isTail }
        rig.answer(tail, listOf(a.event(NOW - 5)), eose = false)
        runCurrent()
        assertEquals(1, rig.state.pendingNewCount)
        assertFalse(rig.state.showsFailureMessage || rig.state.showsEmptyMessage)
        assertTrue(rig.state.showsPendingPill)
    }

    // Deletions

    /** deleted posts are hidden at ingest and when a deletion is recorded, own pinned posts included */
    @Test
    fun deletedPostsHidden() = runTest {
        val me = Author()
        val other = Author()
        val mine = me.event(NOW - 150)
        val gone = other.event(NOW - 120)
        val rig = makeRig(pubkey = me.pubkey) {
            deletedIds.add(gone.id)
            own = listOf(mine)
        }
        val keep = other.event(NOW - 100)
        start(rig, listOf(keep, gone))
        assertEquals(listOf(keep.id, mine.id), rig.postIds)

        // The user deletes their own post (SocialActionManager.deleteEvent
        // marks it, then EventRepository.removedEvents names it).
        rig.deletedIds.add(mine.id)
        rig.deletions.tryEmit(mine.id)
        runCurrent()
        assertEquals(listOf(keep.id), rig.postIds)
    }

    // Recipe row

    /** an empty answered page reaches the end: the list (caught up + recipe row) shows, not the empty message */
    @Test
    fun emptyWindowShowsTheFinishLine() = runTest {
        val a = Author()
        val rig = makeRig()
        start(rig, emptyList())
        assertTrue(rig.state.finishLine.caughtUp)
        assertTrue(rig.state.isEmpty)
        assertFalse(rig.state.showsEmptyMessage)
        val recipe = a.longForm(NOW - 30 * DAY, d = "pie", recipe = true)
        rig.answer(rig.reqs().first { it.isRecipes }, listOf(recipe))
        runCurrent()
        assertEquals(listOf(recipe.id), rig.state.recipePicks.map { it.id })
    }

    /** the recipe row drops deleted and far-future recipes, and keeps old edits */
    @Test
    fun recipeRowGuards() = runTest {
        val a = Author()
        val gone = a.longForm(NOW - 30 * DAY, d = "gone", recipe = true)
        val rig = makeRig { deletedIds.add(gone.id) }
        start(rig, listOf(a.event(NOW - 100)))
        val future = a.longForm(NOW + 3600, d = "future", recipe = true)
        val oldEdit = a.longForm(NOW - 20 * DAY, d = "classic", publishedAt = NOW - 900 * DAY, recipe = true)
        rig.answer(rig.reqs().first { it.isRecipes }, listOf(gone, future, oldEdit))
        runCurrent()
        assertEquals(listOf(oldEdit.id), rig.state.recipePicks.map { it.id })
    }

    /** the recipe row leaves out recipes the feed already shows, by id and by coordinate */
    @Test
    fun recipeRowExcludesFeedRecipes() = runTest {
        val a = Author()
        val b = Author()
        val rig = makeRig()
        val inFeed = a.longForm(NOW - 100, d = "bisque", recipe = true)
        val sameId = b.longForm(NOW - 150, d = "chowder", recipe = true)
        start(rig, listOf(inFeed, sameId, b.event(NOW - 200)))
        val olderEdit = a.longForm(NOW - 30 * DAY, d = "bisque", recipe = true) // same coordinate
        val other = b.longForm(NOW - 40 * DAY, d = "bread", recipe = true)
        rig.answer(rig.reqs().first { it.isRecipes }, listOf(inFeed, olderEdit, sameId, other))
        runCurrent()
        assertEquals(listOf(other.id), rig.state.recipePicks.map { it.id })
    }

    // Last visit

    /** paging counts posts, not rows: with the divider above, the 7th post from the end does not page; the 6th does */
    @Test
    fun pagingThresholdCountsPostsNotRows() = runTest {
        val a = Author()
        val b = Author()
        val pubkey = "e".repeat(64)
        // Posts at now-10, now-20, …: the first two are new, so the divider
        // sits after them, above everything the threshold looks at.
        val store = Store().apply { data[FreshLastVisit.key(pubkey)] = (NOW - 25).toString() }
        val rig = makeRig(pubkey = pubkey, store = store)
        start(rig, (0 until 30).map { i -> (if (i % 2 == 0) a else b).event(NOW - 10 - i * 10) })
        val rows = rig.state.rows
        fun rowIndex(id: String) = rows.indexOfFirst { it is FreshDividedRow.Row && it.row.id == id }
        val prefetch = FreshFeedController.LOAD_MORE_PREFETCH
        val posts = rig.state.posts
        val seventhRow = rowIndex(posts[posts.size - prefetch - 1].id)
        assertEquals("the divider shifts the row index by one", posts.size - prefetch, seventhRow)
        val sent = rig.reqs().size

        rig.controller.rowAppeared(seventhRow)
        runCurrent()
        assertEquals("paged one post early: the row index was compared with the post count", sent, rig.reqs().size)
        rig.controller.rowAppeared(rows.indexOfFirst { it is FreshDividedRow.Divider })
        runCurrent()
        assertEquals("the divider is not a post", sent, rig.reqs().size)

        rig.controller.rowAppeared(rowIndex(posts[posts.size - prefetch].id))
        runCurrent()
        assertEquals("the 6th post from the end pages", sent + 1, rig.reqs().size)
    }

    /** last visit: the divider sits before the first post already seen; the newest shown is stored */
    @Test
    fun lastVisitDivider() = runTest {
        val a = Author()
        val b = Author()
        val pubkey = "d".repeat(64)
        val store = Store().apply { data[FreshLastVisit.key(pubkey)] = (NOW - 150).toString() }
        val rig = makeRig(pubkey = pubkey, store = store)
        val posts = listOf(a.event(NOW - 50), b.event(NOW - 100), a.event(NOW - 200), b.event(NOW - 300))
        start(rig, posts)
        val shape = rig.state.rows.map { row ->
            when (row) {
                is FreshDividedRow.Row -> row.row.id
                is FreshDividedRow.Divider -> "divider:${row.newCount}"
            }
        }
        assertEquals(listOf(posts[0].id, posts[1].id, "divider:2", posts[2].id, posts[3].id), shape)
        assertEquals(NOW - 150, rig.state.visitMark)
        assertEquals((NOW - 50).toString(), store.data[FreshLastVisit.key(pubkey)])
    }

    /** the visit mark records the newest row shown, own posts included */
    @Test
    fun visitMarkIncludesOwnPosts() = runTest {
        val me = Author()
        val other = Author()
        val store = Store()
        val rig = makeRig(pubkey = me.pubkey, store = store) { own = listOf(me.event(NOW - 10)) }
        start(rig, listOf(other.event(NOW - 100)))
        assertEquals(2, rig.state.posts.size)
        assertEquals((NOW - 10).toString(), store.data[FreshLastVisit.key(me.pubkey)])
    }

    // Kind plumbing

    /** the optimistic row shows only for kinds Fresh keeps */
    @Test
    fun pendingRowKinds() {
        for (kind in listOf(1, 30023, 1068)) assertTrue(FreshFeedController.showsPendingRow(kind))
        for (kind in listOf(20, 21, 22, 6)) assertFalse(FreshFeedController.showsPendingRow(kind))
    }

    // Rows handed out

    /** each row is announced once for its profile, with the event alone: no relay URL travels with a Fresh post */
    @Test
    fun rowsAreAnnouncedOnceWithoutASource() = runTest {
        val a = Author()
        val rig = makeRig()
        val p1 = a.event(NOW - 100)
        val (_, tail) = start(rig, listOf(p1))
        val p2 = a.event(NOW - 10)
        rig.answer(tail, listOf(p2), eose = false)
        reveal(rig, count = 1)
        assertEquals(listOf(p1.id, p2.id), rig.shown.map { it.id })
        assertEquals(setOf(p1.id, p2.id), rig.persisted.map { it.id }.toSet())
    }
}
