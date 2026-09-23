package cooking.zap.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The composer's attachment-model contract (docs/attachment-model.md): an
 * attachment is a slot on the draft, not text in the editor. These pure
 * functions are the entire bridge between the two, so they're pinned here —
 * what publish puts on the wire and what Preview renders both come from
 * [composeNoteContent].
 */
class ComposerAttachmentsTest {

    // ---- composeNoteContent ----

    @Test
    fun `no prose and no media is empty`() {
        assertEquals("", composeNoteContent("   ", emptyList()))
        assertEquals("", composeNoteContent("", emptyList()))
    }

    @Test
    fun `prose only passes through trimmed`() {
        assertEquals("hello world", composeNoteContent("  hello world  ", emptyList()))
    }

    @Test
    fun `media only publishes bare URLs`() {
        // Byte-identical to what the URL-in-text era published.
        val media = listOf(ComposerMedia("https://x/a.png"), ComposerMedia("https://x/b.png"))
        assertEquals("https://x/a.png\nhttps://x/b.png", composeNoteContent("", media))
    }

    @Test
    fun `prose and media join with a blank line, URLs in slot order`() {
        val media = listOf(
            ComposerMedia("https://x/b.png", alt = "second"),
            ComposerMedia("https://x/a.png", alt = "first")
        )
        assertEquals(
            "look at this\n\nhttps://x/b.png\nhttps://x/a.png",
            composeNoteContent("look at this", media)
        )
    }

    @Test
    fun `empty-url slots contribute nothing`() {
        val media = listOf(ComposerMedia(""), ComposerMedia("https://x/a.png"))
        assertEquals("prose\n\nhttps://x/a.png", composeNoteContent("prose", media))
    }

    // ---- moveItem (the reorder splice) ----

    @Test
    fun `move later shifts intervening items back`() {
        assertEquals(listOf("b", "c", "a"), moveItem(listOf("a", "b", "c"), 0, 2))
    }

    @Test
    fun `move earlier shifts intervening items forward`() {
        assertEquals(listOf("c", "a", "b"), moveItem(listOf("a", "b", "c"), 2, 0))
    }

    @Test
    fun `adjacent swap`() {
        assertEquals(listOf("a", "c", "b"), moveItem(listOf("a", "b", "c"), 1, 2))
    }

    @Test
    fun `out-of-bounds and no-op moves return the input unchanged`() {
        val list = listOf("a", "b")
        assertEquals(list, moveItem(list, -1, 0))
        assertEquals(list, moveItem(list, 0, 5))
        assertEquals(list, moveItem(list, 1, 1))
        assertEquals(emptyList<String>(), moveItem(emptyList(), 0, 0))
    }

    // ---- stripAttachmentUrlLines (draft migration) ----

    @Test
    fun `boundary occurrence is stripped so publish doesn't append twice`() {
        val text = "look at this\nhttps://x/a.png\nhttps://x/b.png"
        val stripped = stripAttachmentUrlLines(text, setOf("https://x/a.png", "https://x/b.png"))
        assertEquals("look at this", stripped)
    }

    @Test
    fun `url inside a sentence is authored prose and survives`() {
        val text = "mirror at https://x/a.png if the first dies"
        assertEquals(text, stripAttachmentUrlLines(text, setOf("https://x/a.png")))
    }

    @Test
    fun `url twice on one line is ambiguous and left alone`() {
        val text = "https://x/a.png https://x/a.png"
        assertEquals(text, stripAttachmentUrlLines(text, setOf("https://x/a.png")))
    }

    @Test
    fun `unknown urls and blank lines are untouched`() {
        val text = "prose\n\nhttps://x/other.png\ntrailing"
        assertEquals(
            text,
            stripAttachmentUrlLines(text, setOf("https://x/a.png"))
        )
    }

    @Test
    fun `no known urls returns input unchanged`() {
        val text = "anything\nat\nall"
        assertEquals(text, stripAttachmentUrlLines(text, emptySet()))
    }

    // ---- bareUrlLines / removeBareUrlLine (pasted-link attach) ----

    @Test
    fun `bare url alone on its line is a candidate`() {
        assertEquals(
            listOf("https://x/a.png"),
            bareUrlLines("look at this\nhttps://x/a.png")
        )
    }

    @Test
    fun `surrounding whitespace still counts as alone`() {
        assertEquals(
            listOf("https://x/a.png"),
            bareUrlLines("  https://x/a.png  ")
        )
    }

    @Test
    fun `url inside a sentence is not a candidate`() {
        assertEquals(emptyList<String>(), bareUrlLines("mirror at https://x/a.png if the first dies"))
    }

    @Test
    fun `two urls on one line match neither`() {
        assertEquals(emptyList<String>(), bareUrlLines("https://x/a.png https://x/b.png"))
    }

    @Test
    fun `non-http schemes and duplicates are not candidates`() {
        assertEquals(
            emptyList<String>(),
            bareUrlLines("wss://relay.example\nnostr:npub1abc\nftp://x/y")
        )
        assertEquals(
            listOf("https://x/a.png"),
            bareUrlLines("https://x/a.png\n\nhttps://x/a.png")
        )
    }

    @Test
    fun `removeBareUrlLine drops exactly the matching line`() {
        assertEquals("prose\ntrailing", removeBareUrlLine("prose\nhttps://x/a.png\ntrailing", "https://x/a.png"))
        assertEquals("prose", removeBareUrlLine("prose\n  https://x/a.png  ", "https://x/a.png"))
        assertEquals("", removeBareUrlLine("https://x/a.png", "https://x/a.png"))
    }

    @Test
    fun `removeBareUrlLine removes only the first occurrence and leaves non-boundary text alone`() {
        assertEquals(
            "https://x/a.png",
            removeBareUrlLine("https://x/a.png\nhttps://x/a.png", "https://x/a.png")
        )
        assertEquals("see https://x/a.png now", removeBareUrlLine("see https://x/a.png now", "https://x/a.png"))
    }

    // ---- parseAttachmentTags (draft persistence, ordered) ----

    @Test
    fun `tag order is slot order`() {
        val tags = listOf(
            listOf("imeta", "url https://x/b.png", "alt second"),
            listOf("imeta", "url https://x/a.png", "m image/jpeg", "dim 800x600", "alt first"),
            listOf("t", "food")
        )
        val media = parseAttachmentTags(tags)
        assertEquals(listOf("https://x/b.png", "https://x/a.png"), media.map { it.url })
        assertEquals("second", media[0].alt)
        assertEquals("first", media[1].alt)
        assertEquals("image/jpeg", media[1].mimeType)
        assertEquals("800x600", media[1].dimensions)
        assertEquals(false, media[1].isVideo)
    }

    @Test
    fun `undescribed and video slots parse with absent alt flagged`() {
        val tags = listOf(
            listOf("imeta", "url https://x/v.mp4", "m video/mp4"),
            listOf("imeta", "url https://x/plain.png")
        )
        val media = parseAttachmentTags(tags)
        assertNull(media[0].alt)
        assertEquals(true, media[0].isVideo)
        assertNull(media[1].alt)
        assertNull(media[1].mimeType)
    }

    @Test
    fun `whitespace-only alt counts as absent`() {
        val media = parseAttachmentTags(listOf(listOf("imeta", "url https://x/a.png", "alt   ")))
        assertNull(media[0].alt)
    }

    // ---- cache codec round trip ----

    @Test
    fun `encode-decode round trip preserves order and fields`() {
        val media = listOf(
            ComposerMedia(
                url = "https://x/a.png",
                alt = "a cake",
                mimeType = "image/jpeg",
                dimensions = "800x600",
                thumbhash = "th1"
            ),
            ComposerMedia(url = "https://x/b.png"),
            ComposerMedia(url = "https://x/v.mp4", mimeType = "video/mp4", isVideo = true)
        )
        assertEquals(media, decodeMediaFromCache(encodeMediaForCache(media)))
    }

    @Test
    fun `alt with tabs, newlines and backslashes survives the round trip`() {
        val media = listOf(ComposerMedia(url = "https://x/a.png", alt = "line1\nline2\ttabbed\\slash"))
        assertEquals(media, decodeMediaFromCache(encodeMediaForCache(media)))
    }

    @Test
    fun `empty and malformed cache input decodes to no attachments`() {
        assertEquals(emptyList<ComposerMedia>(), decodeMediaFromCache(null))
        assertEquals(emptyList<ComposerMedia>(), decodeMediaFromCache(""))
        assertEquals(emptyList<ComposerMedia>(), decodeMediaFromCache("garbage-without-tabs\n\n"))
        assertEquals(1, decodeMediaFromCache("https://x/a.png\t\t\t\t").size)
    }

    // ---- sanitizeAltText caps by code points (not UTF-16 units) ----

    @Test
    fun `cap counts code points so emoji never split a surrogate pair`() {
        val emoji = "😀".repeat(ALT_TEXT_MAX_CHARS + 1)
        val sanitized = sanitizeAltText(emoji)!!
        assertEquals(ALT_TEXT_MAX_CHARS, sanitized.codePointCount(0, sanitized.length))
        // The cap landed on a full emoji, not half of one.
        assertEquals(ALT_TEXT_MAX_CHARS * 2, sanitized.length)
    }

    @Test
    fun `ascii at or under the cap is untouched`() {
        assertEquals("a".repeat(ALT_TEXT_MAX_CHARS), sanitizeAltText("a".repeat(ALT_TEXT_MAX_CHARS)))
    }

    @Test
    fun `blank collapses to null`() {
        assertNull(sanitizeAltText("   "))
        assertNull(sanitizeAltText(""))
    }
}
