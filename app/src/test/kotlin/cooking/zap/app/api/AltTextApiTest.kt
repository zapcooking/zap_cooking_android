package cooking.zap.app.api

import cooking.zap.app.nostr.FakeNip98Signer
import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.Nip98HeaderCache
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Alt-text generation API (ask-photo `purpose: "alt"` — alt-text handoff
 * §4). Pure mapping tests pin the response-code → sealed-type contract;
 * the MockWebServer test pins the wire shape: `purpose` in the body, raw
 * base64 (no `data:` prefix), and the NIP-98 payload hash binding the
 * exact bytes sent.
 */
class AltTextApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi
    private lateinit var signer: FakeNip98Signer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        signer = FakeNip98Signer()
        api = ZapCookingApi(
            baseUrl = baseUrl,
            client = OkHttpClient(),
            nip98Cache = Nip98HeaderCache(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success maps output as an editable draft`() {
        val result = ZapCookingApi.mapAltTextResponse(
            200,
            """{"ok":true,"output":"TV test pattern"}"""
        )
        assertEquals(AltTextResult.Success("TV test pattern"), result)
    }

    @Test
    fun `typed error codes win over http status`() {
        assertEquals(
            AltTextResult.NotMember,
            ZapCookingApi.mapAltTextResponse(200, """{"ok":false,"code":"NOT_MEMBER"}""")
        )
        assertEquals(
            AltTextResult.ImageUnreadable,
            ZapCookingApi.mapAltTextResponse(200, """{"ok":false,"code":"IMAGE_UNREADABLE"}""")
        )
        assertEquals(
            AltTextResult.RateLimited(600),
            ZapCookingApi.mapAltTextResponse(200, """{"ok":false,"code":"RATE_LIMITED","retryAfter":600}""")
        )
    }

    @Test
    fun `http status is the fallback when the body has no typed code`() {
        assertEquals(AltTextResult.NotMember, ZapCookingApi.mapAltTextResponse(403, "<html>"))
        assertEquals(AltTextResult.MembershipUnavailable, ZapCookingApi.mapAltTextResponse(503, ""))
        assertEquals(AltTextResult.RateLimited(null), ZapCookingApi.mapAltTextResponse(429, "nope"))
        assertEquals(AltTextResult.ImageUnreadable, ZapCookingApi.mapAltTextResponse(422, "nope"))
    }

    @Test
    fun `ok-but-empty output is an error, never an empty description`() {
        val result = ZapCookingApi.mapAltTextResponse(200, """{"ok":true,"output":"   "}""")
        assertTrue(result is AltTextResult.Error)
    }

    @Test
    fun `membership unavailable never maps to the upsell`() {
        assertEquals(
            AltTextResult.MembershipUnavailable,
            ZapCookingApi.mapAltTextResponse(200, """{"ok":false,"code":"MEMBERSHIP_UNAVAILABLE"}""")
        )
    }

    @Test
    fun `wire request carries raw base64 with purpose alt and hash-bound body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"output":"A TV test pattern"}""")
        )
        val base64 = java.util.Base64.getEncoder().encodeToString("fakejpegbytes".toByteArray())

        val result = api.requestAltText(base64, signer)

        assertEquals(AltTextResult.Success("A TV test pattern"), result)
        val recorded = server.takeRequest()
        assertEquals("/api/zappy/ask-photo", recorded.path)

        val body = recorded.body.readUtf8()
        // Raw base64 only — no data: prefix reaches the wire.
        assertTrue(body.contains(""""image":"$base64""""))
        assertTrue(body.contains(""""purpose":"alt""""))
        assertTrue(!body.contains("data:"))

        // NIP-98 payload tag binds the exact body bytes sent.
        val authEvent = decodeAuthEvent(recorded.getHeader("Authorization")!!)
        assertEquals(Nip98.sha256Hex(body.toByteArray()), tagValue(authEvent, "payload"))
    }

    @Test
    fun `signer rejection maps to SignFailed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        signer.failure = cooking.zap.app.nostr.SignerRejectedException("rejected")
        val result = api.requestAltText("animage", signer)
        assertEquals(AltTextResult.SignFailed, result)
    }

    private fun decodeAuthEvent(header: String): JsonObject {
        assertTrue(header.startsWith("Nostr "))
        val decoded = String(java.util.Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8)
        return Json.parseToJsonElement(decoded).jsonObject
    }

    private fun tagValue(event: JsonObject, name: String): String? =
        event["tags"]!!.jsonArray
            .map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
            .firstOrNull { it.firstOrNull() == name }
            ?.getOrNull(1)
}
