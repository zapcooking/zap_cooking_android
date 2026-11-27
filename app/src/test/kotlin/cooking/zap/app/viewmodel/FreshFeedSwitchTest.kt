package cooking.zap.app.viewmodel

import cooking.zap.app.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rider a: switching to Fresh closes the previous feed's live subscription
 * whatever its type, and nothing from the pool streams into a feed list
 * while Fresh is selected.
 *
 * [FeedSubscriptionManager] needs a live relay pool, so it is not built
 * here. What is checked instead: the pure list of subscriptions the switch
 * closes, for every feed type as the manager names its subscriptions; and,
 * in the manager's source, that the switch uses that list, that every path
 * that could reopen a pool feed stops while Fresh is selected, and that the
 * ids `EventRouter` routes into a feed list are all covered. The device
 * checklist has the end-to-end line (Follows → Fresh → Follows).
 */
class FreshFeedSwitchTest {

    private val mainDir: File = sequenceOf("src/main/kotlin/cooking/zap/app", "app/src/main/kotlin/cooking/zap/app")
        .map(::File).first { it.isDirectory }
    private val manager = File(mainDir, "viewmodel/FeedSubscriptionManager.kt").readText()

    private fun body(signature: String): String {
        val start = manager.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val next = Regex("""\n    (private |internal )?fun """).find(manager, start + signature.length)
        return manager.substring(start, next?.range?.first ?: manager.length)
    }

    /** The live subscription each feed type holds, as the manager names it. */
    private data class Live(val type: FeedType, val feedSubId: String, val relayFeedSubId: String, val liveId: String)

    private val live = listOf(
        Live(FeedType.FOLLOWS, "feed-3", "relay-feed", "feed-3"),
        Live(FeedType.FOR_YOU, "feed-3", "relay-feed", "feed-3"),
        Live(FeedType.EXTENDED_FOLLOWS, "feed-3", "relay-feed", "feed-3"),
        Live(FeedType.LIST, "feed-4", "relay-feed", "feed-4"),
        Live(FeedType.RELAY, "feed-3", "relay-feed-2", "relay-feed-2"),
        Live(FeedType.TRENDING, "feed-3", "trending-feed-5", "trending-feed-5"),
        Live(FeedType.TRENDING, "feed-3", "trending-users-6", "trending-users-6"),
        Live(FeedType.ONLY_FOOD, "feed", "onlyfood-feed-1", "onlyfood-feed-1"),
    )

    @Test
    fun `every feed type is covered by a case`() {
        assertEquals(FeedType.entries.toSet() - FeedType.FRESH, live.map { it.type }.toSet())
    }

    @Test
    fun `switching to Fresh closes the previous feed's live subscription whatever its type`() {
        val engagement = listOf("engage-7", "engage-poll-votes", "engage-notif")
        for (case in live) {
            val closing = FreshFeedSwitch.poolSubscriptionsToClose(case.feedSubId, case.relayFeedSubId, engagement)
            assertTrue("${case.type}: ${case.liveId} must be closed", case.liveId in closing)
            // The author feed keeps running under a relay-backed feed, so both go every time.
            assertTrue("${case.type}: the author feed", case.feedSubId in closing)
            assertTrue("${case.type}: the relay-backed feed", case.relayFeedSubId in closing)
            assertTrue("${case.type}: paging and prefetches", closing.containsAll(FreshFeedSwitch.FIXED_FEED_SUB_IDS))
            assertTrue("${case.type}: the previous feed's engagement", closing.containsAll(engagement))
            assertEquals("${case.type}: each id once", closing.size, closing.toSet().size)
        }
    }

    @Test
    fun `every subscription id the router puts into a feed list is closed or moved on`() {
        val router = File(mainDir, "viewmodel/EventRouter.kt").readText()
        val isFeedSub = router.substring(router.indexOf("val isFeedSub ="), router.indexOf("if (isRelayFeedSub &&"))
        val fixed = Regex("""subscriptionId == "([^"]+)"""").findAll(isFeedSub).map { it.groupValues[1] }.toList()
        assertEquals(listOf("loadmore", "feed-backfill", "relay-loadmore"), fixed)
        assertTrue(FreshFeedSwitch.FIXED_FEED_SUB_IDS.containsAll(fixed))
        // The other two are the current feed ids, which the switch moves on.
        assertEquals(1, Regex("""subscriptionId == feedSubId""").findAll(isFeedSub).count())
        assertEquals(1, Regex("""subscriptionId == relayFeedSubId""").findAll(isFeedSub).count())
    }

    @Test
    fun `the switch closes what the list names, moves both feed ids on, and opens no feed`() {
        val suspend = body("private fun suspendPoolFeedsForFresh()")
        val take = suspend.indexOf("FreshFeedSwitch.poolSubscriptionsToClose(feedSubId, relayFeedSubId, activeEngagementSubIds)")
        val moveFeed = suspend.indexOf("feedSubId = \"feed-\$feedGeneration\"")
        val moveRelay = suspend.indexOf("relayFeedSubId = \"relay-feed-\$relayFeedGeneration\"")
        val close = suspend.indexOf("for (subId in closing) relayPool.closeOnAllRelays(subId)")
        assertTrue("ids are taken before they move on", take in 0 until moveFeed && take < moveRelay)
        assertTrue("and closed", close > take)
        for (job in listOf("feedEoseJob", "relayFeedEoseJob", "relayStatusMonitorJob", "forYouSupplementaryJob")) {
            assertTrue("$job is cancelled", suspend.contains("$job?.cancel()"))
        }
        assertTrue(suspend.contains("mainFeedSuspended = true"))
        for (opens in listOf("subscribeByAuthors", "sendToRelayOrEphemeral", "resubscribeFeed(", "restartRelayFeed(", "subscribeRelayFeed(")) {
            assertFalse("the switch must not call $opens", suspend.contains(opens))
        }

        val setFeedType = body("fun setFeedType(type: FeedType)")
        val branch = setFeedType.substring(setFeedType.indexOf("FeedType.FRESH -> {"), setFeedType.indexOf("FeedType.FOR_YOU, FeedType.FOLLOWS"))
        assertTrue(
            "pool feeds close before Fresh opens its socket",
            branch.indexOf("suspendPoolFeedsForFresh()") in 0 until branch.indexOf("freshHooks?.selected()"),
        )
    }

    @Test
    fun `no path reopens a pool feed while Fresh is selected`() {
        // resubscribeFeed is called from reconnects, follow changes and startup retries.
        val resubscribe = body("fun resubscribeFeed()")
        val guard = resubscribe.indexOf("if (_feedType.value == FeedType.FRESH) {")
        assertTrue(guard >= 0)
        assertTrue("the guard returns", resubscribe.substring(guard, guard + 500).contains("return"))
        for (later in listOf("feedGeneration++", "relayPool.closeOnAllRelays", "subscribeByAuthors")) {
            assertTrue("the guard comes before $later", guard < resubscribe.indexOf(later))
        }

        // Reconnect: subscribeFeed returns before resubscribeFeed and restartRelayFeed.
        val subscribe = body("fun subscribeFeed()")
        val afterLanding = subscribe.substring(subscribe.lastIndexOf("if (_feedType.value == FeedType.FRESH) {"))
        assertTrue(afterLanding.indexOf("return") in 0 until afterLanding.indexOf("resubscribeFeed()"))
        // Startup: a Fresh landing subscribes no pool feed.
        val landing = subscribe.substring(subscribe.indexOf("if (landing == FeedType.FRESH) {"), subscribe.indexOf("if (isRelayBackedFeed(landing))"))
        assertTrue(landing.contains("return"))
        for (opens in listOf("resubscribeFeed()", "restartRelayFeed(")) assertFalse(landing.contains(opens))

        // Paging and refresh on the pool do nothing for Fresh.
        assertTrue(body("fun loadMore()").contains("if (_feedType.value == FeedType.FRESH) return"))
        assertTrue(body("fun refreshFeed()").contains("if (type == FeedType.FRESH) return"))
        // Fresh is not a relay-backed feed: restartRelayFeed has no branch for it.
        assertFalse(body("private fun restartRelayFeed(").contains("FeedType.FRESH"))
    }

    @Test
    fun `an author feed picked after Fresh resubscribes, directly or through a relay-backed feed`() {
        val setFeedType = body("fun setFeedType(type: FeedType)")
        val author = setFeedType.substring(setFeedType.indexOf("FeedType.FOR_YOU, FeedType.FOLLOWS, FeedType.EXTENDED_FOLLOWS -> {"))
        val suspended = author.indexOf("if (mainFeedSuspended) {")
        assertTrue(suspended in 0 until author.indexOf("resubscribeFeed()"))
        // Only resubscribeFeed clears the mark, so Fresh → OnlyFood → Follows still resubscribes.
        assertEquals(1, Regex("""mainFeedSuspended = false\n""").findAll(body("fun resubscribeFeed()")).count())
        assertTrue(setFeedType.contains("freshHooks?.deselected()"))
    }

    @Test
    fun `engagement for Fresh rows reads Fresh's own list`() {
        val shown = body("private fun shownFeedEvents()")
        assertTrue(shown.contains("_feedType.value == FeedType.FRESH -> freshHooks?.posts() ?: emptyList()"))
        assertTrue(body("fun subscribeEngagementForFeed()").contains("val feedEvents = shownFeedEvents()"))
        assertTrue(body("fun onViewportChanged(").contains("val feedEvents = shownFeedEvents()"))
    }

    // Kind plumbing (iOS `kindPlumbing`, `shipsOn`)

    @Test
    fun `Fresh is stored as FRESH and restored only while the flag is on`() {
        assertEquals("FRESH", FeedType.FRESH.name)
        assertEquals(FeedType.FRESH, FreshFeedSwitch.landing("FRESH", freshEnabled = true, default = FeedType.ONLY_FOOD))
        assertEquals(FeedType.ONLY_FOOD, FreshFeedSwitch.landing("FRESH", freshEnabled = false, default = FeedType.ONLY_FOOD))
        // Every other stored choice is untouched by the flag; no choice and an unknown name are the default.
        for (type in FeedType.entries - FeedType.FRESH) {
            for (enabled in listOf(true, false)) {
                assertEquals(type, FreshFeedSwitch.landing(type.name, enabled, default = FeedType.ONLY_FOOD))
            }
        }
        assertEquals(FeedType.ONLY_FOOD, FreshFeedSwitch.landing(null, freshEnabled = true, default = FeedType.ONLY_FOOD))
        assertEquals(FeedType.ONLY_FOOD, FreshFeedSwitch.landing("GONE", freshEnabled = true, default = FeedType.ONLY_FOOD))
    }

    @Test
    fun `Fresh cannot be selected while the flag is off`() {
        assertFalse(FreshFeedSwitch.selectable(FeedType.FRESH, freshEnabled = false))
        assertTrue(FreshFeedSwitch.selectable(FeedType.FRESH, freshEnabled = true))
        for (type in FeedType.entries - FeedType.FRESH) assertTrue(FreshFeedSwitch.selectable(type, freshEnabled = false))
        val setFeedType = body("fun setFeedType(type: FeedType)")
        assertTrue(
            "the check is the first thing setFeedType does",
            setFeedType.indexOf("if (!FreshFeedSwitch.selectable(type, FeatureFlags.FRESH_FEED_ENABLED)) return") in
                0 until setFeedType.indexOf("val prev"),
        )
        // The picker entry is behind the same flag.
        val screen = File(mainDir, "ui/screen/FeedScreen.kt").readText()
        val entry = screen.indexOf("viewModel.setFeedType(FeedType.FRESH)")
        val gate = screen.lastIndexOf("FeatureFlags.FRESH_FEED_ENABLED", entry)
        assertTrue(gate in 0 until entry && entry - gate < 600)
        assertEquals(1, Regex("""setFeedType\(FeedType\.FRESH\)""").findAll(screen).count())
    }

    @Test
    fun `ships off`() {
        assertFalse(FeatureFlags.FRESH_FEED_ENABLED)
        // The default landing feed is unchanged; Fresh as the default for new sign-ins is a later step.
        assertTrue(manager.contains("FreshFeedSwitch.landing(savedName, FeatureFlags.FRESH_FEED_ENABLED, default = FeedType.ONLY_FOOD)"))
    }
}
