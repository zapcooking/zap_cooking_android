package cooking.zap.app.fresh

import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of iOS `FreshSpacingTests` (web `spacing.test.ts`), one case for one. */
class FreshSpacingTest {
    private data class Post(val id: String, val author: String)

    private fun p(id: String, author: String) = Post(id, author)
    private fun parse(vararg specs: String) = specs.map { it.split(":").let { (id, a) -> p(id, a) } }
    private fun authors(list: List<Post>) = list.joinToString("") { it.author }
    private fun ids(list: List<Post>) = list.map { it.id }
    private fun space(before: List<Post>, incoming: List<Post>) =
        FreshSpacing.spaceAuthors(before, incoming) { it.author }
    private fun spacer() = FreshStreamSpacer<Post>(id = { it.id }, author = { it.author })

    // spaceAuthors

    @Test
    fun spreadsARunOfOneAuthorThroughThePage() {
        val page = parse("1:A", "2:A", "3:A", "4:B", "5:C", "6:D", "7:E")
        assertEquals("ABCADEA", authors(space(emptyList(), page)))
    }

    @Test
    fun keepsEachAuthorsPostsInTheirOrder_deferralOnly() {
        val out = space(emptyList(), parse("1:A", "2:A", "3:B", "4:A", "5:C", "6:D"))
        assertEquals(listOf("1", "2", "4"), out.filter { it.author == "A" }.map { it.id })
        assertEquals(listOf("1", "3", "5", "2", "6", "4"), ids(out))
    }

    @Test
    fun respectsThePostsAlreadyOnScreen() {
        val out = space(parse("0:X", "1:A"), parse("2:A", "3:B", "4:C"))
        assertEquals("BCA", authors(out))
    }

    @Test
    fun keepsTheOrderWhenSpacingIsImpossible() {
        assertEquals(listOf("1", "2", "3"), ids(space(emptyList(), parse("1:A", "2:A", "3:A"))))
    }

    @Test
    fun leavesAnAlreadySpacedPageUntouched() {
        val page = parse("1:A", "2:B", "3:C", "4:A")
        assertEquals(page, space(emptyList(), page))
    }

    @Test
    fun keepsEveryPostExactlyOnce() {
        val pattern = "ABAAC"
        val page = (0 until 30).map { p(it.toString(), pattern[it % 5].toString()) }
        assertEquals(ids(page).sorted(), ids(space(emptyList(), page)).sorted())
    }

    // the first page, streamed (StreamSpacer)

    @Test
    fun neverMovesOrReordersWhatIsAlreadyPlaced_whileStreamingOrAtEose() {
        val sp = spacer()
        val stream = parse("a1:A", "a2:A", "a3:A", "b1:B", "a4:A", "c1:C", "a5:A", "a6:A", "b2:B", "d1:D")
        var prev = emptyList<String>()
        for (post in stream) {
            val cur = ids(sp.push(post))
            assertEquals(prev, cur.take(prev.size))
            prev = cur
        }
        // The page's final list comes in the relay's order, not arrival order.
        val added = sp.finish(listOf(p("e1", "E")) + stream.reversed() + p("a7", "A"))
        val final = ids(sp.placed)
        assertEquals(prev, final.take(prev.size))
        assertEquals(final.drop(prev.size), ids(added))
        assertEquals((ids(stream) + listOf("e1", "a7")).sorted(), final.sorted())
    }

    @Test
    fun holdsAPostTooCloseToItsAuthor_andPlacesItWhenAnotherAuthorMakesRoom() {
        val sp = spacer()
        sp.push(p("a1", "A"))
        assertEquals(listOf("a1"), ids(sp.push(p("a2", "A"))))
        assertEquals(listOf("a1", "b1"), ids(sp.push(p("b1", "B"))))
        assertEquals(listOf("a1", "b1", "c1", "a2"), ids(sp.push(p("c1", "C"))))
    }

    @Test
    fun spacesAuthorsAsFarAsTheStreamAllows() {
        val sp = spacer()
        val stream = parse("a1:A", "a2:A", "b1:B", "b2:B", "c1:C", "c2:C", "a3:A")
        for (post in stream) sp.push(post)
        sp.finish(stream)
        val pks = sp.placed.map { it.author }
        assertEquals(stream.size, pks.size)
        for (i in 1 until pks.size) {
            for (d in 1 until FreshSpacing.AUTHOR_GAP) {
                if (i - d >= 0) assertNotEquals("at $i, back $d", pks[i - d], pks[i])
            }
        }
    }

    @Test
    fun aPageAllFromOneCook_stillArrivesWholeInOrderAtTheEnd() {
        val sp = spacer()
        for (id in listOf("a1", "a2", "a3")) sp.push(p(id, "A"))
        assertEquals(listOf("a1"), ids(sp.placed))
        sp.finish(emptyList())
        assertEquals(listOf("a1", "a2", "a3"), ids(sp.placed))
    }

    @Test
    fun eoseAddsOnlyWhatDidNotStream_aDuplicateIsIgnored() {
        val sp = spacer()
        sp.push(p("a1", "A"))
        sp.push(p("a1", "A"))
        val added = sp.finish(parse("a1:A", "b1:B"))
        assertEquals(listOf("b1"), ids(added))
        assertEquals(listOf("a1", "b1"), ids(sp.placed))
        assertTrue(sp.has("b1"))
    }

    // Android-only

    @Test
    fun nostrEventsSpaceByPubkey() {
        fun e(id: String, pk: String) =
            NostrEvent(id = id, pubkey = pk, created_at = 0, kind = 1, tags = emptyList(), content = "", sig = "")
        val page = listOf(e("1", "A"), e("2", "A"), e("3", "B"), e("4", "C"))
        assertEquals(listOf("1", "3", "4", "2"), FreshSpacing.spaceAuthors(emptyList(), page).map { it.id })
        val sp = FreshStreamSpacer.forEvents()
        page.forEach { sp.push(it) }
        assertEquals(listOf("1", "3", "4", "2"), sp.placed.map { it.id })
    }
}
