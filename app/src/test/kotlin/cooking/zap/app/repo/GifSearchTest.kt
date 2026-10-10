package cooking.zap.app.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gifs.nostr.build search logic — ported from the web client's
 * src/lib/gifSearch.test.ts (frontend PR #827), itself ported from Sidecar's
 * test/gif-picker.test.js, the reference implementation. What matters is what
 * a bad answer must not do: put a non-https link or an unknown format into a
 * published note, or ask for an offset the API rejects.
 */
class GifSearchTest {

    private val base = "https://gifs.nostr.build/api/v1"

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun paramsOf(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        return if (query.isEmpty()) emptyMap() else query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    // --- URL shapes -------------------------------------------------------

    @Test
    fun `asks for safe results, one page at a time, directly from the API`() {
        val url = gifSearchUrl(base, "  good morning  ", 24)
        assertTrue(url.startsWith("$base/search?"))
        val p = paramsOf(url)
        assertEquals("good morning", p["q"])
        assertEquals("24", p["offset"])
        assertEquals(GIF_PAGE_SIZE.toString(), p["limit"])
        assertEquals("1", p["safe"])
    }

    @Test
    fun `caps the query at 500 characters`() {
        val p = paramsOf(gifSearchUrl(base, "x".repeat(900), 0))
        assertEquals(500, p["q"]!!.length)
    }

    @Test
    fun `points suggestions at the suggest endpoint`() {
        val url = gifSuggestUrl(base, "gm")
        assertTrue(url.startsWith("$base/suggest?"))
        val p = paramsOf(url)
        assertEquals("6", p["limit"])
        assertEquals("1", p["safe"])
        assertNull(p["offset"])
    }

    // --- gifFromItem ------------------------------------------------------

    /** The baseline item, with the given fields replaced (web: `{...item, ...over}`). */
    private fun itemWith(vararg overrides: Pair<String, JsonElement>): JsonElement {
        val base = json(
            """
            {
              "id": "aa.gif",
              "url": "https://image.nostr.build/aa.gif",
              "width": 480, "height": 270,
              "bytes": 123456,
              "format": "gif",
              "title": " Good morning ",
              "previews": {
                "small":  {"width": 120, "height": 68},
                "medium": {"width": 320, "height": 180, "still": "https://p/aa-m.png"},
                "w240":   {"width": 240, "height": 135, "animated": "https://p/aa-240.webp", "still": "https://p/aa-240.png"}
              }
            }
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonObject }
        return kotlinx.serialization.json.JsonObject(
            base.toMutableMap().apply { for ((k, v) in overrides) put(k, v) }
        )
    }

    private fun str(s: String): JsonElement = kotlinx.serialization.json.JsonPrimitive(s)
    private fun num(n: Int): JsonElement = kotlinx.serialization.json.JsonPrimitive(n)

    @Test
    fun `carries its link, its size and the animated w240 preview`() {
        val gif = gifFromItem(itemWith())
        assertEquals(
            Gif(
                url = "https://image.nostr.build/aa.gif",
                preview = "https://p/aa-240.webp",
                width = 480.0,
                height = 270.0,
                title = "Good morning"
            ),
            gif
        )
    }

    @Test
    fun `falls back to the first frame when the GIF is too big to animate`() {
        val still = gifFromItem(
            itemWith(
                "previews" to json("""{"w240": {"still": "https://p/bb.png"}}""")
            )
        )
        assertEquals("https://p/bb.png", still?.preview)
    }

    @Test
    fun `falls back to the medium preview when there is no w240`() {
        val medium = gifFromItem(
            itemWith(
                "previews" to json("""{"medium": {"still": "https://p/m.png"}}""")
            )
        )
        assertEquals("https://p/m.png", medium?.preview)
    }

    @Test
    fun `rejects anything that cannot go into a note as it is`() {
        val bad = listOf(
            itemWith("url" to str("http://image.nostr.build/aa.gif")),
            itemWith("url" to str("javascript:alert(1)")),
            itemWith("format" to str("mp4")),
            itemWith("width" to num(0)),
            itemWith("height" to str("tall")),
            itemWith("previews" to kotlinx.serialization.json.JsonNull),
            itemWith("previews" to json("""{"w240": {"animated": "http://p/x.webp"}}""")),
            json("\"aa.gif\""),
            json("null")
        )
        for (candidate in bad) {
            assertNull(gifFromItem(candidate))
        }
    }

    // --- parseGifPage -----------------------------------------------------

    @Test
    fun `drops what it cannot show and says where the next one starts`() {
        val page = parseGifPage(
            json("""{"count": 3, "offset": 0, "items": [${itemRaw()}, {"id": "bb", "format": "mp4"}]}""")
        )
        assertEquals(1, page.gifs.size)
        // The offset counts every item the API sent, shown or not.
        assertEquals(2, page.next)
        assertNull(parseGifPage(json("""{"count": 3, "offset": 2, "items": [${itemRaw()}]}""")).next)
    }

    @Test
    fun `stops paging at the list and at the last offset the API accepts`() {
        val page = { count: Int, offset: Int, n: Int ->
            parseGifPage(
                json("""{"count": $count, "offset": $offset, "items": [${List(n) { itemRaw() }.joinToString(",")}]}""")
            )
        }
        assertNull(page(200, 192, 8).next)
        assertEquals(192, page(500, 168, 24).next)
        assertNull(page(500, 192, 24).next)
        // An empty page ends the list whatever count claims.
        assertNull(parseGifPage(json("""{"count": 50, "offset": 0, "items": []}""")).next)
    }

    @Test
    fun `answers junk with an empty page`() {
        assertEquals(GifPage(emptyList(), null), parseGifPage(null))
        assertEquals(GifPage(emptyList(), null), parseGifPage(json("""{"items": "nope"}""")))
    }

    // --- parseGifSuggestions ----------------------------------------------

    @Test
    fun `trims, dedupes and caps the terms`() {
        val terms = parseGifSuggestions(
            json(
                """
                {"terms": [
                  {"term": " gm "}, {"term": "gm"}, {"term": ""}, {"nope": 1}, null,
                  {"term": "gn"}, {"term": "good"}, {"term": "great"},
                  {"term": "gg"}, {"term": "go"}, {"term": "gl"}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(listOf("gm", "gn", "good", "great", "gg", "go"), terms)
        assertEquals(emptyList<String>(), parseGifSuggestions(json("{}")))
    }

    // --- gifErrorKind -----------------------------------------------------

    @Test
    fun `reads a refused key or a down service as unavailable, not retryable`() {
        assertEquals(GifErrorKind.UNAVAILABLE, gifErrorKind(403))
        assertEquals(GifErrorKind.UNAVAILABLE, gifErrorKind(401))
        assertEquals(GifErrorKind.UNAVAILABLE, gifErrorKind(503))
    }

    @Test
    fun `asks for patience on the rate limit`() {
        assertEquals(GifErrorKind.RATE_LIMITED, gifErrorKind(429))
    }

    @Test
    fun `blames the connection for everything else`() {
        assertEquals(GifErrorKind.CONNECTION, gifErrorKind(500))
        assertEquals(GifErrorKind.CONNECTION, gifErrorKind(0))
    }

    // --- gifTopicsFor -----------------------------------------------------

    // gm leads 04:00–17:59 local; gn takes the evening and overnight.
    @Test
    fun `opens on gm through the day`() {
        assertEquals("gm", gifTopicsFor(4).first())
        assertEquals("gm", gifTopicsFor(9).first())
        assertEquals("gm", gifTopicsFor(17).first())
    }

    @Test
    fun `opens on gn in the evening and overnight`() {
        assertEquals("gn", gifTopicsFor(18).first())
        assertEquals("gn", gifTopicsFor(21).first())
        assertEquals("gn", gifTopicsFor(3).first())
    }

    @Test
    fun `reorders without dropping a topic`() {
        assertEquals(gifTopicsFor(9).size, gifTopicsFor(21).size)
        assertEquals(GIF_TOPICS.size, gifTopicsFor(21).size)
    }

    // --- paging -----------------------------------------------------------

    @Test
    fun `loads the next page near the bottom, not while loading or after the last page`() {
        // 23 visible of 24 items: within the 6-from-end threshold.
        assertTrue(shouldLoadMore(loading = false, nextOffset = 24, lastVisibleIndex = 23, totalCount = 24))
        assertFalse(shouldLoadMore(loading = false, nextOffset = 24, lastVisibleIndex = 10, totalCount = 200))
        assertFalse(shouldLoadMore(loading = true, nextOffset = 24, lastVisibleIndex = 23, totalCount = 24))
        assertFalse(shouldLoadMore(loading = false, nextOffset = null, lastVisibleIndex = 23, totalCount = 24))
        assertFalse(shouldLoadMore(loading = false, nextOffset = 24, lastVisibleIndex = -1, totalCount = 0))
    }

    @Test
    fun `re-checks only when the cursor moved forward (a duplicates-only page can not stall or loop)`() {
        assertTrue(pageAdvanced(0, 24))
        assertFalse(pageAdvanced(24, 24))
        assertFalse(pageAdvanced(48, 24))
        assertFalse(pageAdvanced(176, null))
    }

    /** The raw item JSON, for pages built by string concatenation. */
    private fun itemRaw(): String =
        """
        {"id": "aa.gif", "url": "https://image.nostr.build/aa.gif", "width": 480, "height": 270,
         "format": "gif", "title": "Good morning",
         "previews": {"w240": {"animated": "https://p/aa-240.webp", "still": "https://p/aa-240.png"}}}
        """.trimIndent().replace("\n", " ")
}
