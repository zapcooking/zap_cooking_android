package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.Nip22
import cooking.zap.app.nostr.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The degraded-root render set ([seedDescendantClosure]) — the pure half of
 * `ThreadViewModel.rebuildTree`, split out because driving the view-model
 * needs a relay stack.
 *
 * The defect it pins: when the conversation root is unreachable the screen
 * re-roots onto the seed, but the `#E` result set stays scoped to the true
 * root and still carries the seed's ancestors — and the tree builder attaches
 * every missing-parent event to the screen root, so opening the fixture's
 * deepest comment rendered the earlier comments as its descendants. Degraded
 * mode now renders only the seed's descendant closure.
 *
 * `resources/nip22/sidecar-thread.json` is the same real four-deep comment
 * chain `Nip22RealThreadTest` exercises; the kind-1 events that root it were
 * pruned from every relay, which is exactly the degraded scenario.
 */
class ThreadDegradedClosureTest {

    private val rootId: String
    private val chain: List<NostrEvent> // oldest → newest, each answering its predecessor

    init {
        val text = javaClass.getResourceAsStream("/nip22/sidecar-thread.json")
            ?.bufferedReader()?.use { it.readText() }
        checkNotNull(text) { "fixture /nip22/sidecar-thread.json did not load" }
        val raw = Json.parseToJsonElement(text).jsonArray
        chain = raw.map { obj ->
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
        rootId = Nip22.rootEventId(chain[0])!!
    }

    private fun replyTo(parent: NostrEvent, id: String, createdAt: Long): NostrEvent = NostrEvent(
        id = id, pubkey = "5".repeat(64), created_at = createdAt, kind = Nip22.KIND_COMMENT,
        tags = Nip22.buildReplyTags(parent) ?: error("fixture event must be a comment"),
        content = "degraded-closure synthetic", sig = ""
    )

    @Test
    fun openingTheDeepestCommentExcludesItsAncestors() {
        val deepest = chain[3]
        // What a degraded open holds: the whole root-scoped result — the
        // seed's ancestors (here, the whole chain above it), a top-level
        // sibling comment on the true root, and one child of the seed.
        val topLevelSibling = NostrEvent(
            id = "s".repeat(64), pubkey = "6".repeat(64), created_at = 1,
            kind = Nip22.KIND_COMMENT,
            tags = listOf(
                listOf("E", rootId, "", "6".repeat(64)),
                listOf("e", rootId, "", "6".repeat(64)),
                listOf("K", "1"),
                listOf("k", "1"),
                listOf("P", "6".repeat(64)),
                listOf("p", "6".repeat(64)),
            ),
            content = "sibling", sig = ""
        )
        val child = replyTo(deepest, id = "d".repeat(64), createdAt = 9)
        val held = chain + topLevelSibling + child

        // The seed itself renders as the screen root (it is never in the
        // closure); its held child is the only event that renders below it —
        // the ancestors a re-root-to-seed would have mis-filed under it and
        // the root-level sibling both stay out.
        assertEquals(
            setOf(child.id),
            seedDescendantClosure(deepest.id, held),
        )
    }

    @Test
    fun lateArrivingChildJoinsOnTheNextRebuild() {
        val deepest = chain[3]
        val child = replyTo(deepest, id = "d".repeat(64), createdAt = 9)

        assertEquals("nothing renders below the seed yet", emptySet<String>(), seedDescendantClosure(deepest.id, chain))
        assertEquals(
            "the closure is recomputed per rebuild, so a child whose event " +
                "lands after its parent's does not stay stranded",
            setOf(child.id),
            seedDescendantClosure(deepest.id, chain + child),
        )
    }

    @Test
    fun missingMidChainEventTruncatesItsSubtree() {
        val deepest = chain[3]
        val child = replyTo(deepest, id = "d".repeat(64), createdAt = 9)
        val grandchild = replyTo(child, id = "g".repeat(64), createdAt = 10)

        assertEquals(
            "both descendants render while the whole chain is held",
            setOf(child.id, grandchild.id),
            seedDescendantClosure(deepest.id, chain + listOf(child, grandchild)),
        )
        // The child never arrives (or arrives later); the grandchild's chain
        // of held events cannot be walked back to the seed, so it stays out —
        // a truncated-but-correct branch beats a mis-rooted one.
        assertEquals(
            emptySet<String>(),
            seedDescendantClosure(deepest.id, chain + grandchild),
        )
    }

    @Test
    fun fullyHeldChainIsFullyReachableFromItsHead() {
        // The filter must only cut what cannot be walked to. Opening the
        // thread on the chain's head (every later comment answers its
        // predecessor, all held) reaches the whole chain below it — degraded
        // mode loses nothing the seed actually roots.
        val closure = seedDescendantClosure(chain[0].id, chain)
        assertEquals(chain.drop(1).map { it.id }.toSet(), closure)
    }
}
