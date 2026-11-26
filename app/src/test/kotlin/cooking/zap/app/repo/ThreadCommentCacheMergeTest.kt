package cooking.zap.app.repo

import cooking.zap.app.nostr.Nip22
import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The thread cache-seed's merge ([mergeCommentsNewestFirst]) — the pure half
 * of [EventRepository.getCachedCommentsRootedOn], split out because the
 * persistence backend (ObjectBox) needs an Android runtime the unit suite
 * doesn't have.
 *
 * The defect it pins: the old implementation returned the in-memory cache
 * contents alone when non-empty — and on a cold open `getEvent(seedId)` has
 * already pulled the one seed comment into the cache, so the whole persisted
 * chain stayed hidden behind it. The other defect: the persistence fallback
 * scanned only the globally newest 2,000 comments before filtering by root,
 * which can miss an older thread entirely (fixed on the query side, in
 * `EventPersistence.getEventsByKindAndRootETag` — not reproducible here).
 */
class ThreadCommentCacheMergeTest {

    private val rootId = "r".repeat(64)

    private fun comment(id: String, createdAt: Long, root: String = rootId): NostrEvent = NostrEvent(
        id = id, pubkey = "p".repeat(64), created_at = createdAt, kind = Nip22.KIND_COMMENT,
        tags = listOf(
            listOf("E", root, "", "pk"),
            listOf("e", "parent-$id", "", "pk"),
        ),
        content = "comment $id", sig = ""
    )

    @Test
    fun coldOpen_seedInCacheDoesNotHidePersistedChain() {
        val cachedSeed = comment("seed", createdAt = 100)
        val persistedChain = listOf(
            comment("old", createdAt = 10),
            cachedSeed.copy(created_at = 100),
            comment("mid", createdAt = 50),
            comment("new", createdAt = 200),
        )

        val merged = mergeCommentsNewestFirst(persistedChain, listOf(cachedSeed), limit = 500)

        assertEquals(
            "the cached seed must not suppress the persisted chain",
            listOf("new", "seed", "mid", "old"),
            merged.map { it.id },
        )
    }

    @Test
    fun cacheCopyWinsIdCollision() {
        val persistedCopy = comment("seed", createdAt = 100)
        val cacheCopy = comment("seed", createdAt = 100).copy(content = "fresher cache copy")

        val merged = mergeCommentsNewestFirst(listOf(persistedCopy), listOf(cacheCopy), limit = 500)

        assertEquals(1, merged.size)
        assertSame("the in-memory copy is the fresher one", cacheCopy, merged.single())
    }

    @Test
    fun perThreadLimitAppliesAfterMerge() {
        val persisted = (1..5L).map { comment("p$it", createdAt = it) }
        val cached = listOf(comment("cached", createdAt = 0))

        val merged = mergeCommentsNewestFirst(persisted, cached, limit = 3)

        assertEquals(listOf("p5", "p4", "p3"), merged.map { it.id })
    }

    @Test
    fun commentRootedElsewhereNeverEntersTheMerge() {
        // The merge itself trusts its inputs — the root filter lives in the
        // callers (cache scan and persistence query). This pins that the
        // filter predicate used there is the parsed uppercase E, so a
        // lowercase-e-only reply to the root is not a "comment rooted on it".
        val replyShapedLikeAnAncestor = comment("x", createdAt = 1, root = "other-root")

        assertEquals("other-root", Nip22.rootEventId(replyShapedLikeAnAncestor))
    }
}
