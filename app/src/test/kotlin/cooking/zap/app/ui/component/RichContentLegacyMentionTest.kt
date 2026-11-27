package cooking.zap.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Legacy NIP-08 mentions: pre-NIP-27 clients referenced people and events
 * in `content` with `#[index]` tokens pointing at the tags array. Reported
 * against event 648944ed… ("Nodestrich Early Birds"), whose 39 `#[N]` lines
 * rendered as literal text instead of @mentions. Ports the cross-platform
 * contract from the iOS fix (zapcooking/zapcooking_ios#248) and the web fix
 * (zapcooking/frontend#1043).
 */
class RichContentLegacyMentionTest {

    private val p1 = "1".repeat(64)
    private val p2 = "2".repeat(64)
    private val p3 = "3".repeat(64)

    /** Mirrors the reported event's shape: a leading non-mention tag, then one ["p", …] per user. */
    private val badgeTags = listOf(
        listOf("t", "nodestrich"),
        listOf("p", p1),
        listOf("p", p2),
        listOf("p", p3),
    )

    private fun parse(content: String, tags: List<List<String>> = badgeTags) =
        parseContent(content, tags = tags)

    @Test
    fun `nodestrich style badge list resolves to profile mentions`() {
        val segments = parse("Congratulations to:\n#[1]\n#[2]\n#[3]")
        assertEquals(
            listOf(
                ContentSegment.TextSegment("Congratulations to:\n"),
                ContentSegment.NostrProfileSegment(p1),
                ContentSegment.TextSegment("\n"),
                ContentSegment.NostrProfileSegment(p2),
                ContentSegment.TextSegment("\n"),
                ContentSegment.NostrProfileSegment(p3),
            ),
            segments
        )
    }

    @Test
    fun `index zero counts too`() {
        assertEquals(
            listOf(ContentSegment.NostrProfileSegment(p1), ContentSegment.TextSegment(" said hi")),
            parse("#[0] said hi", tags = listOf(listOf("p", p1)))
        )
    }

    @Test
    fun `profile relay hint carries through`() {
        val segments = parse("hey #[1]", tags = listOf(listOf("e", "abc"), listOf("p", p1, "wss://relay.example")))
        assertEquals(
            listOf(ContentSegment.TextSegment("hey "), ContentSegment.NostrProfileSegment(p1, listOf("wss://relay.example"))),
            segments
        )
    }

    @Test
    fun `event mention resolves to note reference`() {
        val id = "648944ed2691e83b5b8f5754e0f4fb9c3377facfbe81f3682476c1016aa539a2"
        val segments = parse("re #[0]", tags = listOf(listOf("e", id, "wss://relay.example", "reply", p2)))
        assertEquals(
            listOf(ContentSegment.TextSegment("re "), ContentSegment.NostrNoteSegment(id, listOf("wss://relay.example"), p2)),
            segments
        )
    }

    @Test
    fun `out of range index stays text`() {
        assertEquals(
            listOf(ContentSegment.TextSegment("missing #[7] here")),
            parse("missing #[7] here", tags = listOf(listOf("p", p1)))
        )
    }

    @Test
    fun `non person or event tag stays text`() {
        // #[0] on the example event's ["t", …] tag isn't a mention.
        val segments = parse("#[0] #[1]", tags = listOf(listOf("t", "nodestrich"), listOf("p", p1)))
        assertEquals(
            listOf(ContentSegment.TextSegment("#[0] "), ContentSegment.NostrProfileSegment(p1)),
            segments
        )
    }

    @Test
    fun `malformed tag stays text`() {
        assertEquals(
            listOf(ContentSegment.TextSegment("bad #[1] keep")),
            parse("bad #[1] keep", tags = listOf(listOf("p")))
        )
    }

    @Test
    fun `no tags keeps tokens literal`() {
        // Composer preview parses without the event's tags.
        assertEquals(
            listOf(ContentSegment.TextSegment("who is #[1]?")),
            parseContent("who is #[1]?")
        )
    }

    @Test
    fun `adjacent tokens resolve`() {
        assertEquals(
            listOf(ContentSegment.NostrProfileSegment(p1), ContentSegment.NostrProfileSegment(p2)),
            parse("#[0]#[1]", tags = listOf(listOf("p", p1), listOf("p", p2)))
        )
    }

    @Test
    fun `hashtags unaffected`() {
        val segments = parse("#nostr #[0]", tags = listOf(listOf("p", p1)))
        assertEquals(
            listOf(ContentSegment.HashtagSegment("nostr"), ContentSegment.TextSegment(" "), ContentSegment.NostrProfileSegment(p1)),
            segments
        )
    }

    @Test
    fun `non numeric bracket is untouched`() {
        val tags = listOf(listOf("p", p1))
        assertEquals(listOf(ContentSegment.TextSegment("#[1a]")), parseContent("#[1a]", tags = tags))
        assertEquals(listOf(ContentSegment.TextSegment("#[]")), parseContent("#[]", tags = tags))
    }

    @Test
    fun `p3 badge tag set is covered by the list test`() {
        // Guards the fixture itself: all three pubkeys are distinct.
        assertEquals(setOf(p1, p2, p3), badgeTags.drop(1).map { it[1] }.toSet())
    }
}
