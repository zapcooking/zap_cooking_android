package cooking.zap.app.repo

import android.content.Context
import android.content.SharedPreferences
import cooking.zap.app.nostr.MuteList
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MuteRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys: StateFlow<Set<String>> = _blockedPubkeys

    private val _mutedWords = MutableStateFlow<Set<String>>(emptySet())
    val mutedWords: StateFlow<Set<String>> = _mutedWords

    private val _mutedThreads = MutableStateFlow<Set<String>>(emptySet())
    val mutedThreads: StateFlow<Set<String>> = _mutedThreads

    private val _mutedHashtags = MutableStateFlow<Set<String>>(emptySet())
    val mutedHashtags: StateFlow<Set<String>> = _mutedHashtags

    // Copy-on-write + @Volatile. These are read OFF the main thread — OnlyFood's
    // confined collector calls isBlocked()/containsMutedWord(), and the main feed's
    // background processing calls isThreadMuted(). Every mutation assigns a FRESH
    // set instead of mutating in place, so a reader always iterates an immutable
    // snapshot: no torn reads, no ConcurrentModificationException. @Volatile
    // publishes the new reference to those reader threads.
    @Volatile
    private var blockedSet: Set<String> = emptySet()
    @Volatile
    private var wordSet: Set<String> = emptySet()
    @Volatile
    private var threadSet: Set<String> = emptySet()
    @Volatile
    private var hashtagSet: Set<String> = emptySet()
    private var lastUpdated: Long = 0

    init {
        loadFromPrefs()
    }

    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= lastUpdated) return
        applyMutes(event.created_at, Nip51.parseMuteList(event), MuteList())
    }

    suspend fun loadFromEvent(event: NostrEvent, signer: NostrSigner) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= lastUpdated) return
        val publicMutes = Nip51.parseMuteList(event)
        val privateMutes = if (event.content.isNotBlank()) {
            try {
                val decrypted = signer.nip44Decrypt(event.content, signer.pubkeyHex)
                Nip51.parsePrivateTags(decrypted)
            } catch (_: Exception) {
                MuteList()
            }
        } else MuteList()
        applyMutes(event.created_at, publicMutes, privateMutes)
    }

    /**
     * Apply a mute list whose private items the caller already decrypted (a
     * Lazarus restore decrypts them for its review), so this copy covers
     * them without asking the signer again — the next mute edit rebuilds the
     * whole list from it. Threads and hashtags count too: dropping them here
     * would silently remove them on the next publish.
     */
    fun loadFromEvent(event: NostrEvent, privateTags: List<List<String>>) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= lastUpdated) return
        applyMutes(event.created_at, Nip51.parseMuteList(event), privateTags.toMuteList())
    }

    private fun applyMutes(createdAt: Long, publicMutes: MuteList, privateMutes: MuteList) {
        blockedSet = (publicMutes.pubkeys + privateMutes.pubkeys).toSet()
        wordSet = (publicMutes.words + privateMutes.words).toSet()
        threadSet = (publicMutes.threads + privateMutes.threads).toSet()
        hashtagSet = (publicMutes.hashtags + privateMutes.hashtags).toSet()
        _blockedPubkeys.value = blockedSet
        _mutedWords.value = wordSet
        _mutedThreads.value = threadSet
        _mutedHashtags.value = hashtagSet
        lastUpdated = createdAt
        saveToPrefs()
    }

    /**
     * Apply a mute list whose private items the caller already decrypted (a
     * Lazarus restore decrypts them for its review), so this copy covers
     * them without asking the signer again — the next mute edit rebuilds the
     * whole list from it.
     */
    /** created_at of the mute list this copy was built from; 0 when none. */
    fun lastUpdatedAt(): Long = lastUpdated

    fun blockUser(pubkey: String) {
        blockedSet = blockedSet + pubkey
        _blockedPubkeys.value = blockedSet
        saveToPrefs()
    }

    fun unblockUser(pubkey: String) {
        blockedSet = blockedSet - pubkey
        _blockedPubkeys.value = blockedSet
        saveToPrefs()
    }

    fun isBlocked(pubkey: String): Boolean = blockedSet.contains(pubkey)

    fun addMutedWord(word: String) {
        wordSet = wordSet + word.lowercase()
        _mutedWords.value = wordSet
        saveToPrefs()
    }

    fun removeMutedWord(word: String) {
        wordSet = wordSet - word.lowercase()
        _mutedWords.value = wordSet
        saveToPrefs()
    }

    fun containsMutedWord(content: String): Boolean {
        // Snapshot the volatile reference once: the set is never mutated in place,
        // so iterating this local can't observe a concurrent write.
        val words = wordSet
        if (words.isEmpty()) return false
        val lower = content.lowercase()
        return words.any { lower.contains(it) }
    }

    fun muteThread(rootEventId: String) {
        threadSet = threadSet + rootEventId
        _mutedThreads.value = threadSet
        saveToPrefs()
    }

    fun unmuteThread(rootEventId: String) {
        threadSet = threadSet - rootEventId
        _mutedThreads.value = threadSet
        saveToPrefs()
    }

    fun isThreadMuted(rootEventId: String): Boolean = threadSet.contains(rootEventId)

    fun getBlockedPubkeys(): Set<String> = blockedSet.toSet()

    fun getMutedWords(): Set<String> = wordSet.toSet()

    fun getMutedThreads(): Set<String> = threadSet.toSet()

    fun getMutedHashtags(): Set<String> = hashtagSet.toSet()

    fun clear() {
        _blockedPubkeys.value = emptySet()
        _mutedWords.value = emptySet()
        _mutedThreads.value = emptySet()
        _mutedHashtags.value = emptySet()
        blockedSet = emptySet()
        wordSet = emptySet()
        threadSet = emptySet()
        hashtagSet = emptySet()
        lastUpdated = 0
        prefs.edit().clear().apply()
    }

    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("blocked_pubkeys", blockedSet.toSet())
            .putStringSet("muted_words", wordSet.toSet())
            .putStringSet("muted_threads", threadSet.toSet())
            .putStringSet("muted_hashtags", hashtagSet.toSet())
            .putLong("mute_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("mute_updated", 0)
        val pubkeys = prefs.getStringSet("blocked_pubkeys", null)
        if (pubkeys != null) {
            blockedSet = pubkeys.toSet()
            _blockedPubkeys.value = blockedSet
        }
        val words = prefs.getStringSet("muted_words", null)
        if (words != null) {
            wordSet = words.toSet()
            _mutedWords.value = wordSet
        }
        val threads = prefs.getStringSet("muted_threads", null)
        if (threads != null) {
            threadSet = threads.toSet()
            _mutedThreads.value = threadSet
        }
        val hashtags = prefs.getStringSet("muted_hashtags", null)
        if (hashtags != null) {
            hashtagSet = hashtags.toSet()
            _mutedHashtags.value = hashtagSet
        }
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_mutes_$pubkeyHex" else "wisp_mutes"
    }
}

/** Decrypted mute items a caller already holds (a Lazarus restore), as a [MuteList]. */
private fun List<List<String>>.toMuteList(): MuteList {
    val pubkeys = mutableSetOf<String>()
    val words = mutableSetOf<String>()
    val threads = mutableSetOf<String>()
    val hashtags = mutableSetOf<String>()
    for (tag in this) {
        if (tag.size < 2) continue
        when (tag[0]) {
            "p" -> pubkeys.add(tag[1])
            "word" -> words.add(tag[1])
            "e" -> threads.add(tag[1])
            "t" -> hashtags.add(tag[1])
        }
    }
    return MuteList(pubkeys, words, threads, hashtags)
}
