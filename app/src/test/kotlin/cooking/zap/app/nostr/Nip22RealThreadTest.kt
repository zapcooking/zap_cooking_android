package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NIP-22 mixed-thread handling, exercised against real relay bytes rather
 * than a hand-built fixture.
 *
 * `resources/nip22/sidecar-thread.json` is the tail of the conversation
 * Sidecar documented in dmnyc/sidecar#326, re-fetched from public relays on
 * 2026-09-22 — four kind-1111 comments, signatures as published. The two
 * kind-1 events that root the thread had already been dropped by every relay
 * queried, which is its own argument for reading comments properly: the
 * comments outlived the notes they hang off.
 *
 * Ports wisp-ios `Nip22RealThreadTests`.
 */
class Nip22RealThreadTest {

    /** Chronological, oldest first. */
    private val events: List<NostrEvent> by lazy {
        val text = javaClass.getResourceAsStream("/nip22/sidecar-thread.json")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("fixture /nip22/sidecar-thread.json did not load", text)
        val raw = Json.parseToJsonElement(text!!).jsonArray
        raw.map { obj ->
            val o = obj.jsonObject
            NostrEvent(
                id = o["id"]!!.jsonPrimitive.content,
                pubkey = o["pubkey"]!!.jsonPrimitive.content,
                created_at = o["created_at"]!!.jsonPrimitive.long,
                kind = o["kind"]!!.jsonPrimitive.int,
                tags = o["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } },
                content = o["content"]!!.jsonPrimitive.content,
                sig = o["sig"]!!.jsonPrimitive.content,
            )
        }.sortedBy { it.created_at }
    }

    private val rootId: String by lazy { Nip22.rootEventId(events[0])!! }

    @Test
    fun fixtureLoaded_fourComments() {
        assertEquals(4, events.size)
        assertTrue(events.all { Nip22.isComment(it) })
    }

    /**
     * Every comment is rooted on a **kind-1** note. This is the case the old
     * helper could not see at all — it only read the external `I` form — and
     * the reason these were invisible to counting.
     */
    @Test
    fun everyCommentIsRootedOnAKind1Note() {
        for (e in events) {
            assertEquals("1", Nip22.rootKindRaw(e))
            assertNotNull(Nip22.rootEventId(e))
            // Rooted on an event, so the external accessor stays quiet.
            assertNull(Nip22.externalRoot(e))
        }
        // All four hang off the same root.
        assertEquals(1, events.mapNotNull { Nip22.rootEventId(it) }.toSet().size)
    }

    /** Scope parsing: `E` and `e` are different tags. The uppercase tag names
     *  the thread root, the lowercase one the immediate parent — for the
     *  nested comments those are never the same id. */
    @Test
    fun uppercaseRootDiffersFromLowercaseParentOnNestedComments() {
        for (e in events) {
            val root = Nip22.rootEventId(e)
            val parent = Nip22.parentEventId(e)
            assertNotNull(root)
            assertNotNull(parent)
            // The first comment answers a mid-thread kind-1 note, not the root;
            // the rest answer their predecessor comment. None of the four has
            // parent == root.
            assertTrue("E and e must not fold: ${e.id.take(8)}", root != parent)
        }
    }

    /**
     * The parent chain walks back one event at a time, and `k` flips from
     * 1 to 1111 at the switch — which is exactly the signal the notification
     * caption reads.
     */
    @Test
    fun parentChainWalksBackAndFlipsKind() {
        val ids = events.map { it.id }
        val parents = events.map { Nip22.parentEventId(it) }
        // Each comment after the first names its predecessor.
        for (i in 1 until events.size) {
            assertEquals(
                "comment ${i} should answer ${ids[i - 1].take(8)}",
                ids[i - 1], parents[i],
            )
        }
        // The first answered a note; the rest answered comments.
        assertEquals(1, Nip22.parentKind(events[0]))
        for (e in events.drop(1)) {
            assertEquals(Nip22.KIND_COMMENT, Nip22.parentKind(e))
        }
    }

    /**
     * Every comment in the chain belongs on the thread screen through its
     * uppercase `E` root — including the nested ones a lowercase-`e` check
     * rejects, because their `e` names the parent comment, not the root.
     * This is the ingest guard the live reply stream uses; before it existed,
     * everything below the first reply never rendered.
     */
    @Test
    fun realChainThreadsOffRootAtEveryDepth() {
        val targets = setOf(rootId)
        for (e in events) {
            assertTrue("${e.id.take(8)} must thread off the root", Nip22.threadsOffRoot(e, targets))
        }
        // A stranger's comment naming none of the thread's ids stays out.
        val unrelated = NostrEvent(
            id = "x", pubkey = "pk", created_at = 0, kind = Nip22.KIND_COMMENT,
            tags = listOf(
                listOf("E", "other-root", "", "pk"),
                listOf("e", "other-parent", "", "pk"),
            ),
            content = "", sig = ""
        )
        assertFalse(Nip22.threadsOffRoot(unrelated, targets))
    }

    /**
     * A thread opened on a nested comment must re-root at the conversation
     * root (uppercase `E`), not at the comment's immediate parent —
     * `Nip10.getRootId` is what the thread screen consults, and it used to
     * read only lowercase `e`.
     */
    @Test
    fun nip10RootResolvesToTheConversationRoot() {
        for (e in events) {
            assertEquals("${e.id.take(8)}", rootId, Nip10.getRootId(e))
        }
    }

    /**
     * Replying to the deepest comment: kind 1111 with the root scope carried
     * verbatim and the lowercase side pointing at that comment. This is what
     * the composer publishes where it used to publish a kind-1.
     */
    @Test
    fun replyToDeepestCommentCarriesRootScopeForward() {
        val deepest = events[3]
        val tags = Nip22.buildReplyTags(deepest)
        assertNotNull("reply tags should be built for an event-rooted comment", tags)
        val replyTags = tags!!
        assertTrue(replyTags.any { it.size >= 2 && it[0] == "E" && it[1] == rootId })
        assertTrue(replyTags.any { it.size >= 2 && it[0] == "K" && it[1] == "1" })
        assertTrue(replyTags.contains(listOf("e", deepest.id, "", deepest.pubkey)))
        assertTrue(replyTags.contains(listOf("k", Nip22.KIND_COMMENT.toString())))
        assertTrue(replyTags.contains(listOf("p", deepest.pubkey)))
        // The reply is a comment, not a NIP-10 note: no root/reply markers.
        assertFalse(replyTags.any {
            it.size >= 4 && it[0] == "e" && (it[3] == "root" || it[3] == "reply")
        })
    }

    /** The live check from the port spec: the thread REQ must reach all four
     *  comments — `#E` on the root matches every one of them where `#e` on the
     *  root matches none (the comments' lowercase `e` names their parents). */
    @Test
    fun commentRootFilterMatchesAllFourWhereParentFilterAloneCannot() {
        val eOnly = events.count { e -> e.tags.any { it.size >= 2 && it[0] == "e" && it[1] == rootId } }
        val bigE = events.count { e -> Nip22.rootEventId(e) == rootId }
        assertEquals("a #e(root)-only REQ strands the whole chain", 0, eOnly)
        assertEquals("a #E(root) filter reaches all four", 4, bigE)
    }
}
