package cooking.zap.app.api

import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.Nip98HeaderCache
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerCancelledException
import cooking.zap.app.nostr.SignerRejectedException
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.nostr.toHex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Scheduled-posts API client, against a fake HTTP layer (MockWebServer).
 * Nothing here reaches zap.cooking or a relay.
 *
 * The contract under test is `docs/schedule-contract.md` in
 * zapcooking/frontend: request shapes, NIP-98 binding (bare `u`, payload
 * on POST only, a fresh header per request), every error code, the 401
 * and `bad_relays` single retries, and 409-as-success.
 */
class ScheduleApiTest {

    /**
     * Signs for real with a throwaway key (the unit suite carries the host
     * JVM secp256k1), honouring `createdAt` for everything except NIP-98
     * auth events, which get a distinct increasing time so two headers are
     * never byte-equal. [tamper] rewrites a non-auth event after signing,
     * standing in for a signer that changes what it was given.
     */
    private class FakeScheduleSigner(
        private val keys: Keys.Keypair = Keys.generate(),
    ) : NostrSigner {
        override val pubkeyHex: String = keys.pubkey.toHex()

        /** A genuine signature by this signer's key over [event]'s fields as they now stand. */
        fun resign(event: NostrEvent): NostrEvent =
            NostrEvent.create(keys.privkey, keys.pubkey, event.kind, event.content, event.tags, event.created_at)

        var authSignCount = 0
        var eventSignCount = 0
        var failure: Exception? = null
        var tamper: ((NostrEvent) -> NostrEvent)? = null

        override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
            failure?.let { throw it }
            if (kind == Nip98.KIND) {
                authSignCount++
                return NostrEvent.create(keys.privkey, keys.pubkey, kind, content, tags, 1_700_000_000L + authSignCount)
            }
            eventSignCount++
            val signed = NostrEvent.create(keys.privkey, keys.pubkey, kind, content, tags, createdAt)
            return tamper?.invoke(signed) ?: signed
        }

        override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String =
            throw UnsupportedOperationException()

        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String =
            throw UnsupportedOperationException()
    }

    private lateinit var server: MockWebServer
    private lateinit var signer: FakeScheduleSigner
    private lateinit var baseUrl: String
    /** Only for the one test that needs verification to fail on an otherwise good event. */
    private var forceSignatureInvalid = false

    private val now = 1_800_000_000L
    private val publishAt = now + 3600
    private val eventId = "c".repeat(64)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        signer = FakeScheduleSigner()
        forceSignatureInvalid = false
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** Real [NostrEvent.verifySignature] unless a test forces a failure. */
    private fun api() = if (forceSignatureInvalid) {
        ZapCookingApi(baseUrl, OkHttpClient(), Nip98HeaderCache(), nowSeconds = { now }, verifyEventSignature = { false })
    } else {
        ZapCookingApi(baseUrl, OkHttpClient(), Nip98HeaderCache(), nowSeconds = { now })
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun created(relays: String = "[]") =
        json(201, """{"id":"$eventId","publish_at":$publishAt,"relay_mode":"all","relays":$relays,"status":"pending"}""")

    private fun signedEvent(kind: Int = 1, content: String = "dinner at eight"): NostrEvent =
        NostrEvent.createUnsigned(signer.pubkeyHex, kind, content, listOf(listOf("t", "foodstr")), publishAt)
            .withSignature("0".repeat(128))

    private fun authEvent(request: RecordedRequest): JsonObject {
        val header = request.getHeader("Authorization")!!
        assertTrue(header.startsWith("Nostr "))
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8)
        return Json.parseToJsonElement(decoded).jsonObject
    }

    private fun JsonObject.tag(name: String): String? =
        this["tags"]!!.jsonArray.map { it.jsonArray }
            .firstOrNull { it[0].jsonPrimitive.content == name }?.get(1)?.jsonPrimitive?.content

    private fun failureOf(result: ScheduleEventResult): ScheduleFailure =
        (result as ScheduleEventResult.Failed).failure

    // --- signForSchedule ---

    @Test
    fun sign_returnsTheEventAskedFor_datedAtThePublishTime() = runBlocking {
        val tags = listOf(listOf("t", "foodstr"))
        val result = api().signForSchedule(signer, 1, "hello", tags, publishAt)

        val event = (result as ScheduleSignResult.Signed).event
        assertEquals(publishAt, event.created_at)
        assertEquals(1, event.kind)
        assertEquals("hello", event.content)
        assertEquals(tags, event.tags)
        assertEquals(signer.pubkeyHex, event.pubkey)
        assertTrue(event.verifySignature())
        assertEquals(1, signer.eventSignCount)
    }

    @Test
    fun sign_signedOut_whenThereIsNoSigner() = runBlocking {
        val result = api().signForSchedule(null, 1, "hello", emptyList(), publishAt)
        assertEquals(ScheduleSignResult.Failed(ScheduleFailure.SignedOut), result)
    }

    @Test
    fun sign_refusesThreeMinutesOrLess_withoutAskingTheSigner() = runBlocking {
        val result = api().signForSchedule(signer, 1, "hello", emptyList(), now + 180)
        assertEquals(ScheduleSignResult.Failed(ScheduleFailure.BadPublishTime), result)
        assertEquals(0, signer.eventSignCount)
    }

    @Test
    fun sign_acceptsJustOverThreeMinutes() = runBlocking {
        val result = api().signForSchedule(signer, 1, "hello", emptyList(), now + 181)
        assertTrue(result is ScheduleSignResult.Signed)
    }

    @Test
    fun sign_refusesAPastTime() = runBlocking {
        val result = api().signForSchedule(signer, 1, "hello", emptyList(), now - 10)
        assertEquals(ScheduleSignResult.Failed(ScheduleFailure.BadPublishTime), result)
    }

    @Test
    fun sign_refusesNinetyDaysOrMore_andAcceptsJustUnder() = runBlocking {
        val ninetyDays = 90L * 86_400L
        assertEquals(
            ScheduleSignResult.Failed(ScheduleFailure.BadPublishTime),
            api().signForSchedule(signer, 1, "hello", emptyList(), now + ninetyDays),
        )
        assertEquals(0, signer.eventSignCount)
        assertTrue(api().signForSchedule(signer, 1, "hello", emptyList(), now + ninetyDays - 1) is ScheduleSignResult.Signed)
    }

    @Test
    fun sign_signFailed_whenTheSignerRejectsOrIsDismissed() = runBlocking {
        signer.failure = SignerRejectedException("no")
        assertEquals(
            ScheduleSignResult.Failed(ScheduleFailure.SignFailed),
            api().signForSchedule(signer, 1, "hello", emptyList(), publishAt),
        )
        signer.failure = SignerCancelledException("dismissed")
        assertEquals(
            ScheduleSignResult.Failed(ScheduleFailure.SignFailed),
            api().signForSchedule(signer, 1, "hello", emptyList(), publishAt),
        )
    }

    /** A signer may hand back a whole event of its own; each way it can differ is caught. */
    private fun assertSignerChanged(tamper: (NostrEvent) -> NostrEvent) = runBlocking {
        signer.tamper = tamper
        val result = api().signForSchedule(signer, 1, "hello", listOf(listOf("t", "foodstr")), publishAt)
        assertEquals(ScheduleSignResult.Failed(ScheduleFailure.SignerChangedEvent), result)
    }

    // The tampered events below are genuinely signed and verify on their
    // own — exactly what a re-dating signer returns — so it is the field
    // comparison that has to catch them, not the signature check.

    @Test
    fun sign_signerChangedEvent_whenRedatedToNow() =
        assertSignerChanged { signer.resign(it.copy(created_at = now)).also { e -> assertTrue(e.verifySignature()) } }

    @Test
    fun sign_signerChangedEvent_whenContentChanged() =
        assertSignerChanged { signer.resign(it.copy(content = "hello!")) }

    @Test
    fun sign_signerChangedEvent_whenATagWasAdded() =
        assertSignerChanged { signer.resign(it.copy(tags = it.tags + listOf(listOf("client", "other")))) }

    @Test
    fun sign_signerChangedEvent_whenKindChanged() =
        assertSignerChanged { signer.resign(it.copy(kind = 30023)) }

    @Test
    fun sign_signerChangedEvent_whenSignedByAnotherKey() {
        val other = Keys.generate()
        assertSignerChanged {
            NostrEvent.create(other.privkey, other.pubkey, it.kind, it.content, it.tags, it.created_at)
                .also { e -> assertTrue(e.verifySignature()) }
        }
    }

    @Test
    fun sign_signerChangedEvent_whenOnlyTheIdDiffers() =
        assertSignerChanged { it.copy(id = "f".repeat(64)) }

    // Every field is what was asked for; only the real signature check can refuse these.

    @Test
    fun sign_signerChangedEvent_whenTheSignatureIsGarbage() =
        assertSignerChanged { it.copy(sig = "0".repeat(128)) }

    @Test
    fun sign_signerChangedEvent_whenTheSignatureIsAnotherKeysOverTheSameId() {
        val other = Keys.generate()
        assertSignerChanged { it.copy(sig = Keys.sign(other.privkey, it.id.hexToByteArray()).toHex()) }
    }

    @Test
    fun sign_signerChangedEvent_whenASignatureIsOverADifferentEvent() =
        assertSignerChanged { it.copy(sig = signer.resign(it.copy(content = "something else")).sig) }

    @Test
    fun sign_signerChangedEvent_whenVerificationIsForcedToFail() = runBlocking {
        forceSignatureInvalid = true
        val result = api().signForSchedule(signer, 1, "hello", emptyList(), publishAt)
        assertEquals(ScheduleSignResult.Failed(ScheduleFailure.SignerChangedEvent), result)
    }

    // --- scheduleEvent: request shape ---

    @Test
    fun schedule_postsTheEventWithRelays_boundByNip98() = runBlocking {
        server.enqueue(created("""["wss://relay.example.com"]"""))
        val event = signedEvent()

        val result = api().scheduleEvent(signer, event, listOf("wss://relay.example.com", "wss://other.example.com"))

        assertEquals(
            ScheduleEventResult.Scheduled(eventId, publishAt, listOf("wss://relay.example.com"), ScheduleStatus.PENDING, false),
            result,
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/schedule", request.path)
        val bodyString = request.body.readUtf8()
        val body = Json.parseToJsonElement(bodyString).jsonObject
        assertEquals("all", body["relay_mode"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("wss://relay.example.com", "wss://other.example.com"),
            body["relays"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val sentEvent = body["event"]!!.jsonObject
        assertEquals(event.id, sentEvent["id"]!!.jsonPrimitive.content)
        assertEquals(event.pubkey, sentEvent["pubkey"]!!.jsonPrimitive.content)
        assertEquals(event.sig, sentEvent["sig"]!!.jsonPrimitive.content)
        assertEquals("dinner at eight", sentEvent["content"]!!.jsonPrimitive.content)
        assertEquals(1, sentEvent["kind"]!!.jsonPrimitive.content.toInt())
        // created_at must be a JSON number, not a string.
        val createdAt = sentEvent["created_at"] as JsonPrimitive
        assertFalse(createdAt.isString)
        assertEquals(publishAt, createdAt.content.toLong())
        assertEquals(listOf("t", "foodstr"), sentEvent["tags"]!!.jsonArray[0].jsonArray.map { it.jsonPrimitive.content })

        val auth = authEvent(request)
        assertEquals(Nip98.KIND, auth["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals("$baseUrl/api/schedule", auth.tag("u"))
        assertEquals("POST", auth.tag("method"))
        // The payload hash covers the exact bytes sent.
        assertEquals(Nip98.sha256Hex(bodyString.toByteArray(Charsets.UTF_8)), auth.tag("payload"))
    }

    @Test
    fun schedule_omitsRelays_whenThereAreNone() = runBlocking {
        server.enqueue(created())
        api().scheduleEvent(signer, signedEvent(), emptyList())
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("relays"))
    }

    @Test
    fun schedule_neverReusesAHeader_evenForTheSameBody() = runBlocking {
        server.enqueue(created())
        server.enqueue(json(409, """{"error":"already_scheduled","status":"pending"}"""))
        val api = api()
        val event = signedEvent()

        api.scheduleEvent(signer, event)
        api.scheduleEvent(signer, event)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertNotEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
        assertEquals(2, signer.authSignCount)
    }

    @Test
    fun schedule_signedOut_sendsNothing() = runBlocking {
        val result = api().scheduleEvent(null, signedEvent())
        assertEquals(ScheduleFailure.SignedOut, failureOf(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun schedule_eventFromAnotherAccount_isPubkeyMismatch_andSendsNothing() = runBlocking {
        val other = signedEvent().copy(pubkey = "cd".repeat(32))
        val result = api().scheduleEvent(signer, other)
        assertEquals(ScheduleFailure.PubkeyMismatch, failureOf(result))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun schedule_signFailed_whenTheAuthHeaderCannotBeSigned() = runBlocking {
        signer.failure = SignerRejectedException("no")
        val result = api().scheduleEvent(signer, signedEvent())
        assertEquals(ScheduleFailure.SignFailed, failureOf(result))
        assertEquals(0, server.requestCount)
    }

    // --- scheduleEvent: retries ---

    @Test
    fun schedule_on401_retriesOnceWithAFreshHeader_andTheSameBody() = runBlocking {
        server.enqueue(json(401, """{"error":"auth_failed","detail":"event"}"""))
        server.enqueue(created())

        val result = api().scheduleEvent(signer, signedEvent(), listOf("wss://relay.example.com"))

        assertTrue(result is ScheduleEventResult.Scheduled)
        val rejected = server.takeRequest()
        val retry = server.takeRequest()
        assertNotEquals(rejected.getHeader("Authorization"), retry.getHeader("Authorization"))
        assertEquals(rejected.body.readUtf8(), retry.body.readUtf8())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun schedule_on401Twice_isAuthFailedWithDetail_afterExactlyTwoRequests() = runBlocking {
        server.enqueue(json(401, """{"error":"auth_failed","detail":"event"}"""))
        server.enqueue(json(401, """{"error":"auth_failed","detail":"binding"}"""))

        val result = api().scheduleEvent(signer, signedEvent())

        assertEquals(ScheduleFailure.AuthFailed("binding"), failureOf(result))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun schedule_onBadRelays_retriesOnceWithoutRelays() = runBlocking {
        server.enqueue(json(400, """{"error":"bad_relays"}"""))
        server.enqueue(created())

        val result = api().scheduleEvent(signer, signedEvent(), listOf("wss://relay.example.com"))

        assertEquals(
            ScheduleEventResult.Scheduled(eventId, publishAt, emptyList(), ScheduleStatus.PENDING, false),
            result,
        )
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val secondRequest = server.takeRequest()
        val secondBodyString = secondRequest.body.readUtf8()
        val second = Json.parseToJsonElement(secondBodyString).jsonObject
        assertTrue(first.containsKey("relays"))
        assertFalse(second.containsKey("relays"))
        assertEquals(first["event"], second["event"])
        // The retry's header is bound to the retry's (different) body.
        assertEquals(Nip98.sha256Hex(secondBodyString.toByteArray(Charsets.UTF_8)), authEvent(secondRequest).tag("payload"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun schedule_onBadRelaysTwice_givesUpAfterTheOneRetry() = runBlocking {
        server.enqueue(json(400, """{"error":"bad_relays"}"""))
        server.enqueue(json(400, """{"error":"bad_relays"}"""))

        val result = api().scheduleEvent(signer, signedEvent(), listOf("wss://relay.example.com"))

        assertEquals(ScheduleFailure.BadRelays, failureOf(result))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun schedule_onBadRelays_withNoRelaysSent_doesNotRetry() = runBlocking {
        server.enqueue(json(400, """{"error":"bad_relays"}"""))

        val result = api().scheduleEvent(signer, signedEvent(), emptyList())

        assertEquals(ScheduleFailure.BadRelays, failureOf(result))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun schedule_rateLimited_isNotRetried() = runBlocking {
        server.enqueue(json(429, """{"error":"rate_limited"}"""))

        val result = api().scheduleEvent(signer, signedEvent())

        assertEquals(ScheduleFailure.RateLimited, failureOf(result))
        assertEquals(1, server.requestCount)
    }

    // --- scheduleEvent: 409 ---

    @Test
    fun schedule_alreadyScheduled_isSuccess_whenPendingPublishingOrSent() = runBlocking {
        val event = signedEvent()
        for (status in listOf(ScheduleStatus.PENDING, ScheduleStatus.PUBLISHING, ScheduleStatus.SENT)) {
            server.enqueue(json(409, """{"error":"already_scheduled","status":"${status.wire}"}"""))
            val result = api().scheduleEvent(signer, event, listOf("wss://relay.example.com"))
            assertEquals(
                ScheduleEventResult.Scheduled(event.id, publishAt, listOf("wss://relay.example.com"), status, true),
                result,
            )
        }
    }

    @Test
    fun schedule_alreadyScheduled_isAFailure_whenCancelledFailedOrUnknown() = runBlocking {
        server.enqueue(json(409, """{"error":"already_scheduled","status":"cancelled"}"""))
        assertEquals(
            ScheduleFailure.AlreadyScheduled(ScheduleStatus.CANCELLED),
            failureOf(api().scheduleEvent(signer, signedEvent())),
        )
        server.enqueue(json(409, """{"error":"already_scheduled","status":"failed"}"""))
        assertEquals(
            ScheduleFailure.AlreadyScheduled(ScheduleStatus.FAILED),
            failureOf(api().scheduleEvent(signer, signedEvent())),
        )
        server.enqueue(json(409, """{"error":"already_scheduled","status":"archived"}"""))
        assertEquals(
            ScheduleFailure.AlreadyScheduled(null),
            failureOf(api().scheduleEvent(signer, signedEvent())),
        )
    }

    // --- error codes ---

    @Test
    fun everyContractErrorCode_mapsToItsOwnCase() {
        val expected = mapOf(
            """{"error":"auth_failed","detail":"header"}""" to ScheduleFailure.AuthFailed("header"),
            """{"error":"auth_failed"}""" to ScheduleFailure.AuthFailed(null),
            """{"error":"bad_request"}""" to ScheduleFailure.BadRequest,
            """{"error":"bad_relay_mode"}""" to ScheduleFailure.BadRelayMode,
            """{"error":"bad_relays"}""" to ScheduleFailure.BadRelays,
            """{"error":"bad_signature"}""" to ScheduleFailure.BadSignature,
            """{"error":"pubkey_mismatch"}""" to ScheduleFailure.PubkeyMismatch,
            """{"error":"bad_kind"}""" to ScheduleFailure.BadKind,
            """{"error":"kind_not_allowed_for_relay_mode"}""" to ScheduleFailure.KindNotAllowedForRelayMode,
            """{"error":"bad_publish_time"}""" to ScheduleFailure.BadPublishTime,
            """{"error":"too_large"}""" to ScheduleFailure.TooLarge,
            """{"error":"poll_closes_before_publish"}""" to ScheduleFailure.PollClosesBeforePublish,
            """{"error":"already_scheduled","status":"failed"}""" to ScheduleFailure.AlreadyScheduled(ScheduleStatus.FAILED),
            """{"error":"quota_exceeded"}""" to ScheduleFailure.QuotaExceeded,
            """{"error":"rate_limited"}""" to ScheduleFailure.RateLimited,
            """{"error":"bad_status"}""" to ScheduleFailure.BadStatus,
            """{"error":"not_found"}""" to ScheduleFailure.NotFound,
            """{"error":"not_cancellable","status":"sent"}""" to ScheduleFailure.NotCancellable(ScheduleStatus.SENT),
            """{"error":"not_configured"}""" to ScheduleFailure.NotConfigured,
            """{"error":"internal"}""" to ScheduleFailure.Internal,
        )
        for ((body, failure) in expected) {
            assertEquals(body, failure, ScheduleWire.failureOf(ScheduleWire.parseObject(body)))
        }
    }

    @Test
    fun unknownCode_orUnreadableBody_isAGenericFailure() {
        assertEquals(
            ScheduleFailure.Unknown("brand_new_code"),
            ScheduleWire.failureOf(ScheduleWire.parseObject("""{"error":"brand_new_code"}""")),
        )
        assertEquals(ScheduleFailure.Unknown(null), ScheduleWire.failureOf(ScheduleWire.parseObject("<html>502</html>")))
        assertEquals(ScheduleFailure.Unknown(null), ScheduleWire.failureOf(ScheduleWire.parseObject("")))
        assertEquals(ScheduleFailure.Unknown(null), ScheduleWire.failureOf(ScheduleWire.parseObject("[]")))
        // The code decides, not the status: a non-string error is not a code.
        assertEquals(ScheduleFailure.Unknown(null), ScheduleWire.failureOf(ScheduleWire.parseObject("""{"error":42}""")))
    }

    @Test
    fun schedule_serverErrorWithUnreadableBody_isUnknown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        assertEquals(ScheduleFailure.Unknown(null), failureOf(api().scheduleEvent(signer, signedEvent())))
    }

    @Test
    fun schedule_a200WithoutTheCreatedShape_isNotTreatedAsSuccess() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        assertEquals(ScheduleFailure.Unknown(null), failureOf(api().scheduleEvent(signer, signedEvent())))
    }

    @Test
    fun schedule_network_whenTheServerIsUnreachable() = runBlocking {
        server.shutdown()
        assertEquals(ScheduleFailure.Network, failureOf(api().scheduleEvent(signer, signedEvent())))
    }

    // --- listScheduled ---

    private fun row(id: String, status: String, extra: String = ""): String {
        val event = signedEvent(content = "post $id")
        return """{"id":"$id","publish_at":$publishAt,"relay_mode":"all","relays":["wss://relay.example.com"],""" +
            """"status":"$status","attempts":2,"last_error":null,"sent_at":null,"event":${event.toJson()}$extra}"""
    }

    @Test
    fun list_sendsStatusInTheQuery_butSignsTheBareUrl_withNoPayload() = runBlocking {
        server.enqueue(json(200, """{"items":[]}"""))

        val result = api().listScheduled(signer, ScheduleStatus.PENDING)

        assertEquals(ScheduleListResult.Loaded(emptyList()), result)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/schedule?status=pending", request.path)
        val auth = authEvent(request)
        assertEquals("$baseUrl/api/schedule", auth.tag("u"))
        assertEquals("GET", auth.tag("method"))
        assertNull(auth.tag("payload"))
    }

    @Test
    fun list_withoutAStatus_sendsNoQuery() = runBlocking {
        server.enqueue(json(200, """{"items":[]}"""))
        api().listScheduled(signer)
        assertEquals("/api/schedule", server.takeRequest().path)
    }

    @Test
    fun list_parsesRows_inServerOrder() = runBlocking {
        val failed = """{"id":"${"b".repeat(64)}","publish_at":${publishAt - 60},"relay_mode":"all","relays":[],""" +
            """"status":"failed","attempts":10,"last_error":"blocked: spam filter","sent_at":null,""" +
            """"event":${signedEvent(kind = 1068, content = "poll").toJson()}}"""
        val sent = """{"id":"${"d".repeat(64)}","publish_at":${publishAt - 120},"relay_mode":"all","relays":[],""" +
            """"status":"sent","attempts":1,"last_error":null,"sent_at":${publishAt - 90},""" +
            """"event":${signedEvent().toJson()}}"""
        server.enqueue(json(200, """{"items":[${row("a".repeat(64), "pending")},$failed,$sent]}"""))

        val items = (api().listScheduled(signer) as ScheduleListResult.Loaded).items

        assertEquals(listOf("a".repeat(64), "b".repeat(64), "d".repeat(64)), items.map { it.id })
        val first = items[0]
        assertEquals(publishAt, first.publishAt)
        assertEquals(ScheduleStatus.PENDING, first.status)
        assertEquals(listOf("wss://relay.example.com"), first.relays)
        assertEquals(2, first.attempts)
        assertNull(first.lastError)
        assertNull(first.sentAt)
        assertEquals("post ${"a".repeat(64)}", first.event.content)
        assertEquals(publishAt, first.event.created_at)

        assertEquals(ScheduleStatus.FAILED, items[1].status)
        assertEquals("blocked: spam filter", items[1].lastError)
        assertEquals(1068, items[1].event.kind)
        assertEquals(publishAt - 90, items[2].sentAt)
    }

    @Test
    fun list_skipsRowsItCannotRead_ratherThanFailing() = runBlocking {
        val good = row("a".repeat(64), "pending")
        val unknownStatus = row("b".repeat(64), "archived")
        val noEvent = """{"id":"${"d".repeat(64)}","publish_at":$publishAt,"status":"pending"}"""
        val brokenEvent = """{"id":"${"e".repeat(64)}","publish_at":$publishAt,"status":"pending","event":{"id":"x"}}"""
        server.enqueue(json(200, """{"items":[$unknownStatus,$noEvent,"junk",$brokenEvent,$good]}"""))

        val items = (api().listScheduled(signer) as ScheduleListResult.Loaded).items

        assertEquals(listOf("a".repeat(64)), items.map { it.id })
    }

    @Test
    fun list_toleratesFieldsItDoesNotKnow() = runBlocking {
        server.enqueue(json(200, """{"items":[${row("a".repeat(64), "pending", ""","new_field":{"x":1}""")}],"next":null}"""))
        val items = (api().listScheduled(signer) as ScheduleListResult.Loaded).items
        assertEquals(1, items.size)
    }

    @Test
    fun list_badStatus_andA200WithoutItems_areFailures() = runBlocking {
        server.enqueue(json(400, """{"error":"bad_status"}"""))
        assertEquals(ScheduleListResult.Failed(ScheduleFailure.BadStatus), api().listScheduled(signer, ScheduleStatus.SENT))
        server.enqueue(json(200, """{"ok":true}"""))
        assertEquals(ScheduleListResult.Failed(ScheduleFailure.Unknown(null)), api().listScheduled(signer))
    }

    @Test
    fun list_on401_retriesOnceWithAFreshHeader() = runBlocking {
        server.enqueue(json(401, """{"error":"auth_failed","detail":"event"}"""))
        server.enqueue(json(200, """{"items":[]}"""))

        val result = api().listScheduled(signer, ScheduleStatus.FAILED)

        assertEquals(ScheduleListResult.Loaded(emptyList()), result)
        val rejected = server.takeRequest()
        val retry = server.takeRequest()
        assertNotEquals(rejected.getHeader("Authorization"), retry.getHeader("Authorization"))
        assertEquals("/api/schedule?status=failed", retry.path)
    }

    @Test
    fun list_twoCallsInARow_eachSignTheirOwnHeader() = runBlocking {
        server.enqueue(json(200, """{"items":[]}"""))
        server.enqueue(json(200, """{"items":[]}"""))
        val api = api()

        api.listScheduled(signer, ScheduleStatus.PENDING)
        api.listScheduled(signer, ScheduleStatus.FAILED)

        assertNotEquals(server.takeRequest().getHeader("Authorization"), server.takeRequest().getHeader("Authorization"))
        assertEquals(2, signer.authSignCount)
    }

    @Test
    fun list_signedOut_andNetwork() = runBlocking {
        assertEquals(ScheduleListResult.Failed(ScheduleFailure.SignedOut), api().listScheduled(null))
        assertEquals(0, server.requestCount)
        server.shutdown()
        assertEquals(ScheduleListResult.Failed(ScheduleFailure.Network), api().listScheduled(signer))
    }

    // --- cancelScheduled ---

    @Test
    fun cancel_sendsDeleteForTheId_signedForThatUrl_withNoPayload() = runBlocking {
        server.enqueue(json(200, """{"id":"$eventId","status":"cancelled"}"""))

        val result = api().cancelScheduled(signer, eventId)

        assertEquals(ScheduleCancelResult.Cancelled(eventId, alreadyCancelled = false), result)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/schedule/$eventId", request.path)
        assertEquals(0L, request.bodySize)
        val auth = authEvent(request)
        assertEquals("$baseUrl/api/schedule/$eventId", auth.tag("u"))
        assertEquals("DELETE", auth.tag("method"))
        assertNull(auth.tag("payload"))
    }

    @Test
    fun cancel_notCancellable_saysWhatHappened() = runBlocking {
        for (status in listOf(ScheduleStatus.PUBLISHING, ScheduleStatus.SENT, ScheduleStatus.FAILED)) {
            server.enqueue(json(409, """{"error":"not_cancellable","status":"${status.wire}"}"""))
            assertEquals(
                ScheduleCancelResult.Failed(ScheduleFailure.NotCancellable(status)),
                api().cancelScheduled(signer, eventId),
            )
        }
    }

    @Test
    fun cancel_alreadyCancelled_countsAsDone() = runBlocking {
        server.enqueue(json(409, """{"error":"not_cancellable","status":"cancelled"}"""))
        assertEquals(
            ScheduleCancelResult.Cancelled(eventId, alreadyCancelled = true),
            api().cancelScheduled(signer, eventId),
        )
    }

    @Test
    fun cancel_notFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        assertEquals(ScheduleCancelResult.Failed(ScheduleFailure.NotFound), api().cancelScheduled(signer, eventId))
    }

    @Test
    fun cancel_anIdThatIsNotAnEventId_isNotFound_andSendsNothing() = runBlocking {
        for (bad in listOf("", "abc", "C".repeat(64), "c".repeat(63), "../schedule", "c".repeat(64) + "/x")) {
            assertEquals(ScheduleCancelResult.Failed(ScheduleFailure.NotFound), api().cancelScheduled(signer, bad))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancel_a200ThatDoesNotSayCancelled_isNotTreatedAsDone() = runBlocking {
        server.enqueue(json(200, """{"id":"$eventId","status":"pending"}"""))
        assertEquals(ScheduleCancelResult.Failed(ScheduleFailure.Unknown(null)), api().cancelScheduled(signer, eventId))
    }

    @Test
    fun cancel_on401_retriesOnce_thenReportsAuthFailed() = runBlocking {
        server.enqueue(json(401, """{"error":"auth_failed","detail":"event"}"""))
        server.enqueue(json(401, """{"error":"auth_failed","detail":"event"}"""))

        val result = api().cancelScheduled(signer, eventId)

        assertEquals(ScheduleCancelResult.Failed(ScheduleFailure.AuthFailed("event")), result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancel_signedOut_andNetwork() = runBlocking {
        assertEquals(ScheduleCancelResult.Failed(ScheduleFailure.SignedOut), api().cancelScheduled(null, eventId))
        assertEquals(0, server.requestCount)
        server.shutdown()
        assertEquals(ScheduleCancelResult.Failed(ScheduleFailure.Network), api().cancelScheduled(signer, eventId))
    }

    // --- the cache the other endpoints use is left alone ---

    @Test
    fun scheduleCalls_doNotPopulateOrReadTheSharedHeaderCache() = runBlocking {
        val cache = Nip98HeaderCache()
        val api = ZapCookingApi(baseUrl, OkHttpClient(), cache, { now }, { true })
        server.enqueue(json(200, """{"items":[]}"""))
        api.listScheduled(signer)

        // Had the list call cached its header, this would be a hit for the same key.
        val fresh = cache.authHeader(signer, "GET", "$baseUrl/api/schedule")
        assertFalse(fresh.fromCache)
    }
}
