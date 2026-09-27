package cooking.zap.app.lazarus

import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.Nip65
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.BookmarkRepository
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.MuteRepository
import cooking.zap.app.repo.ProfileRepository
import cooking.zap.app.repo.RelayListRepository

/**
 * The app's own copies of the lists Lazarus restores, for the active
 * account. The scan reads the relay list from here, the re-read before a
 * restore reads the list's copy, and a published restore updates it (spec
 * "Recover"): otherwise the app's next edit rebuilds from the clobbered copy
 * and clobbers the list again.
 */
class LazarusLocalStores(
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val profileRepo: ProfileRepository,
    private val relayListRepo: RelayListRepository,
    private val keyRepo: KeyRepository,
    private val eventRepo: EventRepository,
    private val relayPool: RelayPool
) {
    /** The user's relay list (kind 10002) as the app holds it, or null when it holds none. */
    fun relayList(pubkey: String): LazarusUserRelays? {
        if (!relayListRepo.hasRelayList(pubkey)) return null
        return LazarusUserRelays(
            read = relayListRepo.getReadRelays(pubkey).orEmpty(),
            write = relayListRepo.getWriteRelays(pubkey).orEmpty()
        )
    }

    /** The app's configured relays: where it reads and publishes the user's own lists. */
    fun appRelays(): List<String> = keyRepo.getRelays().map { it.url }

    /**
     * The app's copy of [kind]: the created_at of the version it was built
     * from, and that version itself when the event cache still holds one.
     * Null when the app has no copy of the list.
     */
    fun localCopy(kind: Int, pubkey: String): LazarusLocalCopy? {
        val builtFrom = when (kind) {
            3 -> contactRepo.lastUpdatedAt()
            Nip51.KIND_MUTE_LIST -> muteRepo.lastUpdatedAt()
            Nip51.KIND_BOOKMARK_LIST -> bookmarkRepo.lastUpdatedAt()
            0 -> profileRepo.getUpdatedAt(pubkey) ?: 0L
            10002 -> relayListRepo.getUpdatedAt(pubkey) ?: 0L
            Nip51.KIND_DM_RELAYS -> relayListRepo.getDmUpdatedAt(pubkey) ?: 0L
            else -> 0L
        }
        val cached = eventRepo.getCachedEventsByAuthor(pubkey, kind, 1).firstOrNull()
        if (builtFrom <= 0L && cached == null) return null
        return LazarusLocalCopy(createdAt = maxOf(builtFrom, cached?.created_at ?: 0L), event = cached)
    }

    /**
     * Update the app's copy with a published restore. [privateTags] are the
     * restored version's decrypted private items (NIP-51), so a mute list's
     * copy keeps them: the app rebuilds the whole mute list from its copy on
     * the next mute.
     */
    fun applyRestored(event: NostrEvent, privateTags: List<List<String>>?) {
        when (event.kind) {
            3 -> contactRepo.updateFromEvent(event)
            Nip51.KIND_MUTE_LIST -> {
                // A restore of encrypted items is only offered once they're
                // decrypted, so privateTags is missing only when there are none
                if (privateTags != null || getContentEncryption(event.content) == null) {
                    muteRepo.loadFromEvent(event, privateTags.orEmpty())
                }
            }
            Nip51.KIND_BOOKMARK_LIST -> bookmarkRepo.loadFromEvent(event)
            0 -> eventRepo.cacheEvent(event)
            10002 -> {
                relayListRepo.updateFromEvent(event)
                // The relay settings read the saved list; the pool picks it up on
                // its next rebuild, as with any relay list the app receives
                val relays = Nip65.parseRelayList(event)
                if (relays.isNotEmpty()) keyRepo.saveRelays(relays)
            }
            Nip51.KIND_DM_RELAYS -> {
                relayListRepo.updateDmRelaysFromEvent(event)
                val urls = Nip51.parseRelaySet(event)
                keyRepo.saveDmRelays(urls)
                relayPool.updateDmRelays(urls)
            }
            Nip51.KIND_BLOCKED_RELAYS -> {
                val urls = Nip51.parseRelaySet(event)
                keyRepo.saveBlockedRelays(urls)
                relayPool.updateBlockedUrls(urls)
            }
            // Kind 10044 has no local copy in this app.
        }
    }
}
