package cooking.zap.app.fresh

import android.content.SharedPreferences
import cooking.zap.app.db.EventPersistence
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.DeletedEventsRepository
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.MetadataFetcher
import cooking.zap.app.repo.MuteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/** The last-visit mark in a preferences file. Device-local. */
class PrefsFreshVisitStorage(private val prefs: SharedPreferences) : FreshVisitStorage {
    override fun freshVisitValue(key: String): String? = prefs.getString(key, null)
    override fun setFreshVisitValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * The live [FreshFeedController]: the app's repositories behind the
 * controller's seams. Nothing here takes a signer or a signing mode; the
 * account's pubkey is all Fresh needs.
 *
 * No relay URL crosses this file. A Fresh event is kept with
 * [EventRepository.cacheEvent], which takes the event alone, and never with
 * the calls that record where an event came from: the feed relay must not
 * become a relay hint (build spec §1).
 */
object FreshFeedWiring {

    /** The reader's mutes, read live on every use. Hashtags are lowercased here. */
    fun readerRules(muteRepo: MuteRepository): FreshReaderRules = FreshReaderRules(
        isUserBlocked = muteRepo::isBlocked,
        containsMutedWord = muteRepo::containsMutedWord,
        isThreadMuted = muteRepo::isThreadMuted,
        mutedHashtags = muteRepo.getMutedHashtags().mapTo(HashSet()) { it.lowercase().removePrefix("#") },
    )

    /** Any change to the mute list. */
    fun rulesChanged(muteRepo: MuteRepository): Flow<Unit> = merge(
        muteRepo.blockedPubkeys.map { },
        muteRepo.mutedWords.map { },
        muteRepo.mutedThreads.map { },
        muteRepo.mutedHashtags.map { },
    )

    /**
     * The user's own recent posts from the event cache and the on-device
     * store, unfiltered: the controller decides which may be pinned.
     */
    suspend fun ownLocalPosts(
        pubkey: String,
        eventRepo: EventRepository,
        eventPersistence: EventPersistence?,
    ): List<NostrEvent> = withContext(Dispatchers.IO) {
        val limit = FreshFeedController.OWN_POSTS_LIMIT
        FreshPosts.FEED_KINDS.flatMap { kind ->
            eventRepo.getCachedEventsByAuthor(pubkey, kind, limit) +
                (eventPersistence?.getEventsByAuthorAndKind(pubkey, kind, limit) ?: emptyList())
        }.distinctBy { it.id }
    }

    fun controller(
        pubkey: String,
        eventRepo: EventRepository,
        muteRepo: MuteRepository,
        deletedEventsRepo: DeletedEventsRepository,
        eventPersistence: EventPersistence?,
        metadataFetcher: MetadataFetcher,
        visitPrefs: SharedPreferences,
    ): FreshFeedController = FreshFeedController(
        pubkey = pubkey,
        rules = { readerRules(muteRepo) },
        isPrivate = eventRepo::isPrivate,
        isDeleted = deletedEventsRepo::isEventDeleted,
        ownLocalPosts = { ownLocalPosts(pubkey, eventRepo, eventPersistence) },
        persist = { events -> events.forEach(eventRepo::cacheEvent) },
        onShown = { events ->
            for (event in events) {
                if (eventRepo.getProfileData(event.pubkey) == null) metadataFetcher.addToPendingProfiles(event.pubkey)
                metadataFetcher.fetchQuotedEvents(event)
            }
        },
        visitStorage = PrefsFreshVisitStorage(visitPrefs),
        published = eventRepo.publishedEvents,
        rulesChanged = rulesChanged(muteRepo),
        deletions = eventRepo.removedEvents,
    )
}
