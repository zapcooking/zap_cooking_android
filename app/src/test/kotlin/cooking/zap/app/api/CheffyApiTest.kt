package cooking.zap.app.api

import cooking.zap.app.nostr.FakeNip98Signer
import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.Nip98HeaderCache
import cooking.zap.app.nostr.SignerRejectedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Cheffy chat API layer (`POST /api/zappy`) — issue #247.
 *
 * The server requires NIP-98 since frontend `04cf67cd` (2026-08-17) and
 * ignores a body `pubkey`. Pure mapping tests pin the status/body contract
 * against the server's real shapes (frontend `src/routes/api/zappy/+server.ts`
 * at `main` 62846a4). MockWebServer tests pin the Authorization header, the
 * payload-hash identity with the exact bytes sent, that no `pubkey` leaks
 * onto the wire, and the one silent re-sign on a 401.
 */
class CheffyApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi
    private lateinit var signer: FakeNip98Signer
    private lateinit var baseUrl: String

    private val request = CheffyRequest(
        prompt = "What can I make with eggs and spinach?",
        mode = CheffyMode.CHAT.wire,
        messages = listOf(CheffyMessage("user", "hi"), CheffyMessage("assistant", "Hello, chef!")),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        signer = FakeNip98Signer()
        api = ZapCookingApi(baseUrl = baseUrl, client = OkHttpClient(), nip98Cache = Nip98HeaderCache())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- Response mapping (pure) ---

    @Test
    fun map_403_isMembersOnly() {
        assertEquals(
            CheffyResult.MembersOnly,
            ZapCookingApi.mapCheffyResponse(403, """{"ok":false,"error":"Cheffy is available to Cook+ members."}"""),
        )
    }

    @Test
    fun map_401_isErrorWithSignerCopy_notMembersOnly() {
        val result = ZapCookingApi.mapCheffyResponse(401, """{"ok":false,"error":"Authentication required"}""")
        assertEquals(CheffyResult.Error(ZapCookingApi.CHEFFY_AUTH_REJECTED_MESSAGE), result)
    }

    @Test
    fun map_200ok_isReply_trimmed() {
        val result = ZapCookingApi.mapCheffyResponse(200, """{"ok":true,"output":"  Try a spinach omelette.  "}""")
        assertEquals(CheffyResult.Reply("Try a spinach omelette."), result)
    }

    @Test
    fun map_200okFalse_passesServerError() {
        val result = ZapCookingApi.mapCheffyResponse(200, """{"ok":false,"error":"Invalid mode"}""")
        assertEquals(CheffyResult.Error("Invalid mode"), result)
    }

    @Test
    fun map_200okEmptyOutput_isError() {
        assertTrue(ZapCookingApi.mapCheffyResponse(200, """{"ok":true,"output":""}""") is CheffyResult.Error)
    }

    @Test
    fun map_500NonJson_isError() {
        assertTrue(ZapCookingApi.mapCheffyResponse(500, "<html>bad gateway</html>") is CheffyResult.Error)
    }

    @Test
    fun map_429ExperienceUsed_isErrorWithServerCopy() {
        val copy = "Create your free kitchen or unlock Cook+ to keep cooking with Cheffy."
        val result = ZapCookingApi.mapCheffyResponse(429, """{"ok":false,"code":"CHEFFY_EXPERIENCE_USED","error":"$copy"}""")
        assertEquals(CheffyResult.Error(copy), result)
    }

    // --- Wire: NIP-98 header bound to the exact bytes sent, no body pubkey ---

    @Test
    fun send_signsNip98OverExactBodyBytes_andSendsNoPubkey() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Omelette time."}"""))

        val result = api.sendCheffy(request, signer)
        assertEquals(CheffyResult.Reply("Omelette time."), result)

        val recorded = server.takeRequest()
        assertEquals("/api/zappy", recorded.path)
        assertEquals("POST", recorded.method)

        val bodyBytes = recorded.body.readByteArray()
        val body = Json.parseToJsonElement(String(bodyBytes, Charsets.UTF_8)).jsonObject
        assertFalse("server ignores a body pubkey; must not be sent", body.containsKey("pubkey"))
        assertEquals("chat", body["mode"]!!.jsonPrimitive.content)
        assertEquals(2, body["messages"]!!.jsonArray.size)

        val authEvent = decodeAuthEvent(recorded.getHeader("Authorization")!!)
        assertEquals(27235, authEvent["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals(signer.pubkeyHex, authEvent["pubkey"]!!.jsonPrimitive.content)
        assertEquals(Nip98.sha256Hex(bodyBytes), tagValue(authEvent, "payload"))
        assertEquals("$baseUrl/api/zappy", tagValue(authEvent, "u"))
        assertEquals("POST", tagValue(authEvent, "method"))
        assertEquals(1, signer.signCount)
    }

    @Test
    fun send_403_isMembersOnly_withHeaderPresent() = runBlocking {
        server.enqueue(jsonResponse(403, """{"ok":false,"error":"Cheffy is available to Cook+ members."}"""))
        assertEquals(CheffyResult.MembersOnly, api.sendCheffy(request, signer))
        assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith("Nostr "))
    }

    @Test
    fun send_401OnCachedHeader_resignsOnceAndRetries() = runBlocking {
        // Warm the cache with an identical request, then have the server reject
        // the reused header: authedRaw must invalidate, re-sign, retry once.
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"first"}"""))
        server.enqueue(jsonResponse(401, """{"ok":false,"error":"Authentication required"}"""))
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"second"}"""))

        assertEquals(CheffyResult.Reply("first"), api.sendCheffy(request, signer))
        assertEquals(CheffyResult.Reply("second"), api.sendCheffy(request, signer))

        val first = server.takeRequest().getHeader("Authorization")
        val cached = server.takeRequest().getHeader("Authorization")
        val resigned = server.takeRequest().getHeader("Authorization")
        assertEquals(first, cached)
        assertNotEquals(cached, resigned)
        assertEquals(2, signer.signCount)
    }

    @Test
    fun send_signerRejected_isErrorNotNetworkError_noRoundTrip() = runBlocking {
        signer.failure = SignerRejectedException("declined")
        val result = api.sendCheffy(request, signer)
        assertEquals(CheffyResult.Error(ZapCookingApi.CHEFFY_SIGN_FAILED_MESSAGE), result)
        assertEquals(0, server.requestCount)
    }

    // --- helpers ---

    private fun jsonResponse(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun decodeAuthEvent(header: String): JsonObject {
        assertTrue(header.startsWith("Nostr "))
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8)
        return Json.parseToJsonElement(decoded).jsonObject
    }

    private fun tagValue(event: JsonObject, name: String): String? =
        event["tags"]!!.jsonArray
            .map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
            .firstOrNull { it.firstOrNull() == name }
            ?.getOrNull(1)
}
