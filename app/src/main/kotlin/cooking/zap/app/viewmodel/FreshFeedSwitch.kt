package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent

/**
 * The Fresh feed as [FeedSubscriptionManager] sees it. Fresh has its own
 * socket and its own list (`fresh/FreshFeedController`); the manager only
 * tells it when it is shown, and reads its rows for the engagement queries.
 */
interface FreshFeedHooks {
    /** Fresh became the selected feed. */
    fun selected()

    /** Another feed was picked. */
    fun deselected()

    /** The rows Fresh shows now. */
    fun posts(): List<NostrEvent>

    /** Account switch: drop the list and close the socket. */
    fun reset()
}

/**
 * What changes on the shared relay pool when Fresh is picked. Pure, so the
 * hermetic suite can check it for every feed type: the manager itself needs
 * a live pool.
 */
internal object FreshFeedSwitch {

    /**
     * Subscription ids whose events `EventRouter` puts into a feed list
     * whatever the current generation is, plus the list feed's prefetches.
     */
    val FIXED_FEED_SUB_IDS = listOf("loadmore", "relay-loadmore", "feed-backfill", "list-prefetch", "list-prefetch-more")

    /**
     * Every pool subscription to close when Fresh becomes the selected feed,
     * whatever feed was showing: the author feed ([feedSubId]: For You,
     * Follows, Extended, List; it keeps running under a relay-backed feed
     * too), the relay-backed feed ([relayFeedSubId]: a relay or relay set,
     * Trending, OnlyFood), their paging, and the previous feed's engagement
     * queries. Nothing from the pool streams into a feed list after this.
     */
    fun poolSubscriptionsToClose(
        feedSubId: String,
        relayFeedSubId: String,
        engagementSubIds: Collection<String>,
    ): List<String> = (listOf(feedSubId, relayFeedSubId) + FIXED_FEED_SUB_IDS + engagementSubIds).distinct()

    /**
     * The feed to land on at startup. [savedName] is the stored choice, or
     * null when the user never picked one. A stored Fresh is honoured only
     * while the flag is on; an unknown name is no choice.
     */
    fun landing(savedName: String?, freshEnabled: Boolean, default: FeedType): FeedType {
        val saved = savedName?.let { name -> FeedType.entries.firstOrNull { it.name == name } } ?: return default
        if (saved == FeedType.FRESH && !freshEnabled) return default
        return saved
    }

    /** May [type] be selected at all? */
    fun selectable(type: FeedType, freshEnabled: Boolean): Boolean = type != FeedType.FRESH || freshEnabled
}
