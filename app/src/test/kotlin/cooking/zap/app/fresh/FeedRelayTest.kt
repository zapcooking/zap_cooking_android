package cooking.zap.app.fresh

import cooking.zap.app.FeatureFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the pure cases of iOS `FeedRelayNoAuthTests`. */
class FeedRelayTest {

    /** Every spelling of the feed relay a relay path could be handed. */
    private val feedSpellings = listOf(
        "wss://feed.zap.cooking",
        "wss://feed.zap.cooking/",
        "WSS://Feed.Zap.Cooking",
        "wss://feed.zap.cooking:443",
        "wss://feed.zap.cooking/some/path?x=1",
        "wss://feed.zap.cooking.",
        "wss://user@feed.zap.cooking",
        "ws://feed.zap.cooking",
        "https://feed.zap.cooking",
        "feed.zap.cooking",
        "  wss://feed.zap.cooking\n",
    )

    /** Hosts that must keep signing: members relay, a public relay, look-alikes. */
    private val otherRelays = listOf(
        "wss://pantry.zap.cooking",
        "wss://nos.lol",
        "wss://zap.cooking",
        "wss://x.feed.zap.cooking",
        "wss://feed.zap.cooking.example.com",
        "wss://example.com/feed.zap.cooking",
        "wss://example.com?relay=feed.zap.cooking",
        "wss://feed.zap.cooking@example.com",
    )

    @Test
    fun flag_shipsOff() {
        assertFalse(FeatureFlags.FEED_RELAY_LOGIN_ENABLED)
    }

    @Test
    fun hostMatch_everySpellingOfTheFeedRelay() {
        for (url in feedSpellings) assertTrue(url, FeedRelay.isFeedRelay(url))
        assertTrue(FeedRelay.isFeedRelay(FeedRelay.URL))
    }

    @Test
    fun hostMatch_neverALookAlike() {
        for (url in otherRelays + listOf("", "wss://", "not a url")) {
            assertFalse(url, FeedRelay.isFeedRelay(url))
        }
    }

    /** The gate is the flag and the host together, both ways. */
    @Test
    fun loginAllowed_truthTable() {
        assertFalse(FeedRelay.loginAllowed(FeedRelay.URL, feedRelayLoginEnabled = false))
        assertTrue(FeedRelay.loginAllowed(FeedRelay.URL, feedRelayLoginEnabled = true))
        assertTrue(FeedRelay.loginAllowed("wss://pantry.zap.cooking", feedRelayLoginEnabled = false))
        assertTrue(FeedRelay.loginAllowed("wss://pantry.zap.cooking", feedRelayLoginEnabled = true))
    }

    @Test
    fun loginAllowed_shippedDefault_refusesEverySpelling_andNoLookAlike() {
        for (url in feedSpellings) assertFalse(url, FeedRelay.loginAllowed(url))
        for (url in otherRelays) assertTrue(url, FeedRelay.loginAllowed(url))
    }
}
