package cooking.zap.app.repo

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * gifs.nostr.build wire contract — the native-client counterpart of the web
 * app's gifProxy.server.test.ts (frontend PR #827): the key travels as a
 * Bearer header and never in the URL, upstream statuses map to the picker's
 * own messages rather than being flattened, and nothing is requested for a
 * blank query or a missing key.
 */
class GifSearchRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: GifSearchRepository
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api/v1").toString().trimEnd('/')
        repo = GifSearchRepository(baseUrl = baseUrl, apiKey = "test-key", client = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val itemJson =
        """
        {"id": "aa.gif", "url": "https://image.nostr.build/aa.gif", "width": 480, "height": 270,
         "format": "gif", "title": "Good morning",
         "previews": {"w240": {"animated": "https://p/aa-240.webp", "still": "https://p/aa-240.png"}}}
        """.trimIndent().replace("\n", " ")

    @Test
    fun `sends the key as a Bearer header, never in the URL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"count": 0, "offset": 0, "items": []}"""))
        repo.search("pie", offset = 0)
        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue("URL must not carry the key", !recorded.path!!.contains("test-key"))
        assertEquals("/api/v1/search", recorded.path!!.substringBefore("?"))
    }

    @Test
    fun `search parses a page and the next offset`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"count": 100, "offset": 0, "items": [$itemJson]}"""))
        val page = repo.search("gm", offset = 0)
        assertEquals(1, page.gifs.size)
        assertEquals("https://image.nostr.build/aa.gif", page.gifs.first().url)
        assertEquals(1, page.next)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/api/v1/search?"))
        assertTrue(path.contains("q=gm"))
        assertTrue(path.contains("limit=24"))
        assertTrue(path.contains("offset=0"))
        assertTrue(path.contains("safe=1"))
    }

    @Test
    fun `suggest asks for 6 terms and sends no offset`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"terms": [{"term": "gm"}]}"""))
        val terms = repo.suggest("gm")
        assertEquals(listOf("gm"), terms)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/api/v1/suggest?"))
        assertTrue(path.contains("limit=6"))
        assertNull("suggest must not page", path.substringAfter("?").split("&").firstOrNull { it.startsWith("offset=") })
    }

    @Test
    fun `maps upstream statuses to the picker's messages, not a generic failure`() = runBlocking {
        // 401/403/503: refused key or unconfigured service; 429: rate limit;
        // anything else reads as a connection problem.
        val cases = listOf(
            401 to GifErrorKind.UNAVAILABLE,
            403 to GifErrorKind.UNAVAILABLE,
            503 to GifErrorKind.UNAVAILABLE,
            429 to GifErrorKind.RATE_LIMITED,
            500 to GifErrorKind.CONNECTION
        )
        for ((status, kind) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error": $status}"""))
            try {
                repo.search("pie")
                throw AssertionError("expected GifSearchException for HTTP $status")
            } catch (e: GifSearchException) {
                assertEquals(kind, e.kind)
            }
        }
    }

    @Test
    fun `a network failure reads as a connection problem`() {
        server.shutdown()
        runBlocking {
            try {
                repo.search("pie")
                throw AssertionError("expected GifSearchException")
            } catch (e: GifSearchException) {
                assertEquals(GifErrorKind.CONNECTION, e.kind)
            }
        }
    }

    @Test
    fun `a missing key reports search unavailable without calling upstream`() = runBlocking {
        val keyless = GifSearchRepository(baseUrl = baseUrl, apiKey = "", client = OkHttpClient())
        try {
            keyless.search("pie")
            throw AssertionError("expected GifSearchException")
        } catch (e: GifSearchException) {
            assertEquals(GifErrorKind.UNAVAILABLE, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank query answers empty without calling upstream`() = runBlocking {
        assertEquals(GifPage(emptyList(), null), repo.search("   "))
        assertEquals(emptyList<String>(), repo.suggest("   "))
        assertEquals(0, server.requestCount)
    }
}
