package cooking.zap.app.fresh

import cooking.zap.app.nostr.NostrEvent

/**
 * Author spacing, ported from iOS `FreshSpacing.swift` (web
 * `src/lib/freshFeed/spacing.ts`): one cook posting several times in a row
 * shouldn't fill the screen. A newly loaded page is reordered so the same
 * author doesn't appear twice within `gap` consecutive posts where that's
 * possible: a post that would be too close is held back to the earliest slot
 * far enough from the author's last post. Posts already on screen never move;
 * only the incoming page is reordered, and only by deferring (a post is never
 * moved up). A page that is all one author keeps its order.
 */
object FreshSpacing {
    const val AUTHOR_GAP = 3

    fun <T> spaceAuthors(
        before: List<T>,
        incoming: List<T>,
        gap: Int = AUTHOR_GAP,
        author: (T) -> String,
    ): List<T> {
        val keep = maxOf(gap - 1, 0)
        val recent = before.takeLast(keep).map(author)
        val queue = incoming.toMutableList()
        val out = ArrayList<T>(incoming.size)
        while (queue.isNotEmpty()) {
            val window = (recent + out.map(author)).takeLast(keep)
            val i = queue.indexOfFirst { author(it) !in window }.let { if (it < 0) 0 else it }
            out.add(queue.removeAt(i))
        }
        return out
    }

    fun spaceAuthors(before: List<NostrEvent>, incoming: List<NostrEvent>, gap: Int = AUTHOR_GAP): List<NostrEvent> =
        spaceAuthors(before, incoming, gap) { it.pubkey }
}

/**
 * Author spacing for a page that streams in (the first page), ported from web
 * `StreamSpacer`: each post is appended as it arrives unless it would sit
 * within `gap` of its author's last post; then it is held until another
 * author's post makes room. The page's end (EOSE) adds the held posts and
 * whatever the final page has that didn't stream, spaced the same way, at the
 * end. Nothing placed ever moves: what the reader sees mid-stream stays.
 *
 * Not thread-safe: confine it to one coroutine context.
 */
class FreshStreamSpacer<T>(
    private val gap: Int = FreshSpacing.AUTHOR_GAP,
    private val id: (T) -> String,
    private val author: (T) -> String,
) {
    private val _placed = ArrayList<T>()
    private val held = ArrayList<T>()
    private val ids = HashSet<String>()

    /** Placed so far, in order (a copy). */
    val placed: List<T> get() = _placed.toList()

    fun has(id: String): Boolean = id in ids

    /** A streamed post: placed now if it fits, else held. Returns [placed]. */
    fun push(p: T): List<T> {
        if (!ids.add(id(p))) return placed
        held.add(p)
        drain()
        return placed
    }

    /** Place every held post far enough from its author's last one, in arrival order. */
    private fun drain() {
        val keep = maxOf(gap - 1, 0)
        while (true) {
            val window = _placed.takeLast(keep).map(author)
            val i = held.indexOfFirst { author(it) !in window }
            if (i < 0) return
            _placed.add(held.removeAt(i))
        }
    }

    /**
     * The page ended: the held posts and the final page's posts not seen yet
     * are spaced after what is placed, and appended. Returns what was added.
     */
    fun finish(final: List<T>): List<T> {
        val rest = final.filter { ids.add(id(it)) }
        val added = FreshSpacing.spaceAuthors(_placed, held + rest, gap, author)
        held.clear()
        _placed.addAll(added)
        return added
    }

    companion object {
        fun forEvents(gap: Int = FreshSpacing.AUTHOR_GAP): FreshStreamSpacer<NostrEvent> =
            FreshStreamSpacer(gap, id = { it.id }, author = { it.pubkey })
    }
}
