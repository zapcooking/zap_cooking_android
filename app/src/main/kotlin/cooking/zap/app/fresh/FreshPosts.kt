package cooking.zap.app.fresh

import cooking.zap.app.nostr.HiddenRecipes
import cooking.zap.app.nostr.NostrEvent

/**
 * The reader's own rules, as plain lambdas so [FreshPosts] stays JVM-testable
 * with no repo wiring (the [cooking.zap.app.repo.OnlyFoodFilter] pattern).
 * Wire them to `MuteRepository` (`isBlocked`, `containsMutedWord`,
 * `isThreadMuted`, `getMutedHashtags`).
 *
 * @param mutedHashtags lowercase, no leading `#`.
 * @param hellthreadPLimit a post tagging this many people or more is hidden;
 *   null turns the rule off. Android's rule is fixed (OnlyFood uses 25), where
 *   iOS reads a setting.
 */
class FreshReaderRules(
    val isUserBlocked: (pubkey: String) -> Boolean = { false },
    val containsMutedWord: (content: String) -> Boolean = { false },
    val isThreadMuted: (eventId: String) -> Boolean = { false },
    val mutedHashtags: Set<String> = emptySet(),
    val hellthreadPLimit: Int? = FreshPosts.HELLTHREAD_P_LIMIT,
)

/**
 * What the Fresh feed (`wss://feed.zap.cooking`, build spec §1 "Fresh feed
 * relay") shows. A port of iOS `FreshPosts.swift`, itself a port of web
 * `src/lib/freshFeed/posts.ts`: the post kinds, `isOldEdit` (unchanged) and
 * the one question asked before showing a post. Pure.
 *
 * Differences from iOS, all deliberate:
 * - Hashtag mutes apply (web does this; iOS has no hashtag mutes).
 * - The hellthread rule is a fixed p-tag limit, not a setting.
 * - No "reported" sets: Android hides a reported post through the mute list.
 */
object FreshPosts {

    /** The relay's free window: 14 days (web `FREE_WINDOW_SECONDS`). */
    const val FREE_WINDOW_SECONDS = 14L * 24 * 60 * 60

    /** Same limit as `OnlyFoodFilter.HELLTHREAD_P_LIMIT`. */
    const val HELLTHREAD_P_LIMIT = 25

    /** The kinds a Fresh feed request asks for (no 35000: build spec §1). */
    val FEED_KINDS = listOf(1, 30023, 1068)

    /** Lowercase recipe hashtags (web `RECIPE_TAGS`). */
    val RECIPE_TAGS = setOf("zapcooking", "nostrcooking")

    enum class PostKind { NOTE, POLL, RECIPE, ARTICLE }

    /** Kind 35000, or a long-form post tagged as a Zap Cooking recipe (any case). */
    fun isRecipe(kind: Int, tags: List<List<String>>): Boolean {
        if (kind == 35000) return true
        if (kind != 30023) return false
        return tags.any { it.size >= 2 && it[0] == "t" && it[1].lowercase() in RECIPE_TAGS }
    }

    fun postKind(kind: Int, tags: List<List<String>>): PostKind = when {
        kind == 1068 -> PostKind.POLL
        isRecipe(kind, tags) -> PostKind.RECIPE
        kind == 30023 -> PostKind.ARTICLE
        else -> PostKind.NOTE
    }

    /**
     * `published_at`, when the post has a sane one (finite, > 0 and within
     * `Long`). Like web's `Number(v)`, a decimal value counts; it is truncated
     * to seconds. A value too large for `Long` (`"1e100"`) is null.
     */
    fun publishedAt(tags: List<List<String>>): Long? {
        val tag = tags.firstOrNull { it.isNotEmpty() && it[0] == "published_at" } ?: return null
        if (tag.size < 2) return null
        return wholeSeconds(tag[1])
    }

    /**
     * A long-form post first published before the free window and edited
     * since: an edit, not a new post. Kept out of the newest-first feed (the
     * recipe row shows old recipes). [now] in seconds. Ported unchanged.
     */
    fun isOldEdit(kind: Int, tags: List<List<String>>, now: Long): Boolean {
        if (kind != 30023 && kind != 35000) return false
        val p = publishedAt(tags) ?: return false
        return p < now - FREE_WINDOW_SECONDS
    }

    fun isOldEdit(e: NostrEvent, now: Long): Boolean = isOldEdit(e.kind, e.tags, now)

    /**
     * Dropped at ingest whatever the reader's rules: a dev / e2e long-form
     * post on the shared [HiddenRecipes] list, recipe-tagged or not.
     */
    fun isHiddenAtIngest(e: NostrEvent): Boolean {
        if (e.kind != 30023) return false
        val dTag = e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        return HiddenRecipes.isHidden(e.kind, e.pubkey, dTag)
    }

    /**
     * The one question before showing a Fresh post in the newest-first feed.
     * Mutes and the hellthread rule always apply; OnlyFood's food test,
     * hashtag cap and Web of Trust don't (the relay is curated). [rules] null
     * means no mute list is loaded: nothing is muted.
     */
    fun passesFreshFilters(e: NostrEvent, rules: FreshReaderRules?, now: Long): Boolean {
        if (!passesReaderFilters(e, rules)) return false
        if (isOldEdit(e, now)) return false
        return true
    }

    /**
     * The reader's own rules only, plus [HiddenRecipes]: for recipe-row picks,
     * which keep old edits.
     */
    fun passesReaderFilters(e: NostrEvent, rules: FreshReaderRules?): Boolean {
        if (isHiddenAtIngest(e)) return false
        if (rules == null) return true
        if (isMuted(e, rules)) return false
        val limit = rules.hellthreadPLimit
        if (limit != null && e.tags.count { it.size >= 2 && it[0] == "p" } >= limit) return false
        return true
    }

    /**
     * Web `isEventMutedBy`, on the Android mute list. A muted thread id hides
     * the event itself and anything whose `e` tags name it.
     */
    fun isMuted(e: NostrEvent, rules: FreshReaderRules): Boolean {
        if (rules.isUserBlocked(e.pubkey) || rules.isUserBlocked(e.pubkey.lowercase())) return true
        if (e.content.isNotEmpty() && rules.containsMutedWord(e.content)) return true
        if (rules.isThreadMuted(e.id)) return true
        for (tag in e.tags) {
            if (tag.size < 2) continue
            if (tag[0] == "e" && rules.isThreadMuted(tag[1])) return true
            if (tag[0] == "t" && tag[1].lowercase() in rules.mutedHashtags) return true
        }
        return false
    }

    /**
     * A decimal string as whole seconds; null for garbage, zero, negatives,
     * non-finite values and values `Long` cannot hold.
     */
    internal fun wholeSeconds(raw: String): Long? {
        val n = raw.trim().toDoubleOrNull() ?: return null
        if (!n.isFinite() || n <= 0.0) return null
        val t = kotlin.math.truncate(n)
        // 2^63 as a double: anything at or above it does not fit a Long.
        if (t >= 9.223372036854775807E18) return null
        return t.toLong()
    }
}
