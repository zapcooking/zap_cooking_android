package cooking.zap.app.fresh

import cooking.zap.app.nostr.HiddenRecipes
import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `FreshPostsTests` (web `src/lib/freshFeed/posts.test.ts` filter
 * cases), on the Android mute model.
 */
class FreshPostsTest {
    private val now = 2_000_000_000L
    private val day = 86_400L
    private val muted = "b".repeat(64)

    private fun ev(
        id: String = "1".repeat(64),
        pubkey: String = "c".repeat(64),
        kind: Int = 1,
        createdAt: Long = now - 60,
        tags: List<List<String>> = emptyList(),
        content: String = "hello",
    ) = NostrEvent(id = id, pubkey = pubkey, created_at = createdAt, kind = kind, tags = tags, content = content, sig = "s")

    private fun rules(
        blocked: Set<String> = emptySet(),
        words: Set<String> = emptySet(),
        threads: Set<String> = emptySet(),
        hashtags: Set<String> = emptySet(),
        hellthread: Int? = FreshPosts.HELLTHREAD_P_LIMIT,
    ) = FreshReaderRules(
        isUserBlocked = { it in blocked },
        containsMutedWord = { content -> words.any { content.lowercase().contains(it) } },
        isThreadMuted = { it in threads },
        mutedHashtags = hashtags,
        hellthreadPLimit = hellthread,
    )

    private fun crowd(n: Int) = (0 until n).map { listOf("p", it.toString().padStart(64, '0')) }

    // post kinds

    @Test
    fun recipes_kind35000_or30023TaggedZapcookingOrNostrcooking_anyCase() {
        assertTrue(FreshPosts.isRecipe(35000, emptyList()))
        assertTrue(FreshPosts.isRecipe(30023, listOf(listOf("t", "ZapCooking"))))
        assertTrue(FreshPosts.isRecipe(30023, listOf(listOf("t", "nostrcooking"))))
        assertFalse(FreshPosts.isRecipe(30023, listOf(listOf("t", "food"))))
        assertFalse(FreshPosts.isRecipe(1, listOf(listOf("t", "zapcooking"))))
    }

    @Test
    fun mapsEveryFeedKind() {
        assertEquals(FreshPosts.PostKind.NOTE, FreshPosts.postKind(1, emptyList()))
        assertEquals(FreshPosts.PostKind.POLL, FreshPosts.postKind(1068, emptyList()))
        assertEquals(FreshPosts.PostKind.RECIPE, FreshPosts.postKind(35000, emptyList()))
        assertEquals(FreshPosts.PostKind.ARTICLE, FreshPosts.postKind(30023, emptyList()))
    }

    // old edits stay out of the main feed

    @Test
    fun longFormPublishedBeforeTheWindow_isAnEdit() {
        val old = (now - 30 * day).toString()
        assertTrue(FreshPosts.isOldEdit(30023, listOf(listOf("published_at", old)), now))
        assertTrue(FreshPosts.isOldEdit(35000, listOf(listOf("published_at", old)), now))
    }

    @Test
    fun recentOrMissingPublishedAt_andNotes_areNot() {
        assertFalse(FreshPosts.isOldEdit(30023, listOf(listOf("published_at", (now - day).toString())), now))
        assertFalse(FreshPosts.isOldEdit(30023, emptyList(), now))
        assertFalse(FreshPosts.isOldEdit(30023, listOf(listOf("published_at", "garbage")), now))
        assertFalse(FreshPosts.isOldEdit(30023, listOf(listOf("published_at")), now))
        assertFalse(FreshPosts.isOldEdit(1, listOf(listOf("published_at", "1")), now))
    }

    /** iOS review fix (#201): a value the integer type cannot hold is no timestamp. */
    @Test
    fun outOfRangePublishedAt_isNull_andNotAnEdit() {
        for (raw in listOf("1e100", "9.3e18", "1e308", "NaN", "Infinity", "-5", "0")) {
            assertNull(raw, FreshPosts.publishedAt(listOf(listOf("published_at", raw))))
            assertFalse(raw, FreshPosts.isOldEdit(30023, listOf(listOf("published_at", raw)), now))
        }
        assertEquals(1_700_000_000L, FreshPosts.publishedAt(listOf(listOf("published_at", "1700000000.9"))))
    }

    // passesFreshFilters

    @Test
    fun passesAnOrdinaryPost() {
        val r = rules(blocked = setOf(muted), words = setOf("spam"))
        assertTrue(FreshPosts.passesFreshFilters(ev(), r, now))
        assertTrue(FreshPosts.passesFreshFilters(ev(), null, now))
    }

    @Test
    fun appliesMutes_peopleWordsAndHashtags() {
        val r = rules(blocked = setOf(muted), words = setOf("spam"), hashtags = setOf("nsfw"))
        assertFalse(FreshPosts.passesFreshFilters(ev(pubkey = muted), r, now))
        assertFalse(FreshPosts.passesFreshFilters(ev(content = "buy SPAM now"), r, now))
        assertFalse(FreshPosts.passesFreshFilters(ev(tags = listOf(listOf("t", "NSFW"))), r, now))
        assertTrue(FreshPosts.passesFreshFilters(ev(tags = listOf(listOf("t", "dinner"))), r, now))
    }

    @Test
    fun appliesTheHellthreadRule() {
        val e = ev(tags = crowd(FreshPosts.HELLTHREAD_P_LIMIT))
        assertFalse(FreshPosts.passesFreshFilters(e, rules(), now))
        assertTrue(FreshPosts.passesFreshFilters(ev(tags = crowd(FreshPosts.HELLTHREAD_P_LIMIT - 1)), rules(), now))
        // Off, the same post passes.
        assertTrue(FreshPosts.passesFreshFilters(e, rules(hellthread = null), now))
    }

    @Test
    fun dropsOldEdits() {
        val e = ev(kind = 30023, tags = listOf(listOf("published_at", (now - 90 * day).toString())))
        assertFalse(FreshPosts.passesFreshFilters(e, null, now))
    }

    @Test
    fun appliesNoFoodTestOrHashtagCap_theRelayIsCurated() {
        val many = (0 until 12).map { listOf("t", "tag$it") }
        assertTrue(FreshPosts.passesFreshFilters(ev(tags = many, content = "a note about my bike"), rules(), now))
    }

    // passesReaderFilters (recipe-row picks)

    @Test
    fun readerFilters_applyMutesAndHellthread_butKeepOldEdits() {
        val oldTag = listOf("published_at", (now - 90 * day).toString())
        val old = ev(kind = 30023, tags = listOf(oldTag))
        assertTrue(FreshPosts.passesReaderFilters(old, null))
        assertTrue(FreshPosts.passesReaderFilters(old, rules()))
        val crowded = ev(kind = 30023, tags = listOf(oldTag) + crowd(30))
        assertFalse(FreshPosts.passesReaderFilters(crowded, rules()))
        assertFalse(FreshPosts.passesReaderFilters(old, rules(blocked = setOf(old.pubkey))))
    }

    @Test
    fun mutedThreads_hideTheNoteItselfAndRepliesNamingIt() {
        val root = "a".repeat(64)
        val r = rules(threads = setOf(root))
        assertFalse(FreshPosts.passesFreshFilters(ev(id = root), r, now))
        assertFalse(FreshPosts.passesFreshFilters(ev(tags = listOf(listOf("e", root, "", "root"))), r, now))
        assertTrue(FreshPosts.passesFreshFilters(ev(), r, now))
    }

    @Test
    fun hiddenRecipes_droppedAtIngest_forKind30023Only() {
        val parts = HiddenRecipes.COORDINATES.first().split(":", limit = 3)
        val hidden = ev(pubkey = parts[1], kind = 30023, tags = listOf(listOf("d", parts[2]), listOf("t", "zapcooking")))
        assertTrue(FreshPosts.isHiddenAtIngest(hidden))
        assertFalse(FreshPosts.passesReaderFilters(hidden, null))
        assertFalse(FreshPosts.passesFreshFilters(hidden, null, now))
        // Untagged long-form on the list is hidden too: the rule is by coordinate.
        assertTrue(FreshPosts.isHiddenAtIngest(ev(pubkey = parts[1], kind = 30023, tags = listOf(listOf("d", parts[2])))))
        // A d-tag prefix hides under any key.
        assertTrue(FreshPosts.isHiddenAtIngest(ev(kind = 30023, tags = listOf(listOf("d", "ios-2.3-live-publish-1700000000")))))
        // A note by the same author with the same d tag is not a recipe coordinate.
        val note = ev(pubkey = parts[1], kind = 1, tags = listOf(listOf("d", parts[2])))
        assertFalse(FreshPosts.isHiddenAtIngest(note))
        assertTrue(FreshPosts.passesFreshFilters(note, null, now))
    }

    @Test
    fun feedKindsAreTheContractKinds_no35000() {
        assertEquals(listOf(1, 30023, 1068), FreshPosts.FEED_KINDS)
        assertEquals(14 * 86_400L, FreshPosts.FREE_WINDOW_SECONDS)
    }
}
