package cooking.zap.app.fresh

import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** A scripted socket: records what the client sent, and plays the relay. */
internal class FakeFreshSocket(val url: String, private val listener: FreshSocketListener) : FreshSocket {
    val sent = mutableListOf<String>()
    var cancelled = false
        private set
    private var dropped = false

    override fun send(text: String): Boolean {
        if (cancelled || dropped) return false
        sent.add(text)
        return true
    }

    override fun cancel() {
        cancelled = true
    }

    /** A frame from the relay. */
    fun feed(text: String) {
        if (!cancelled && !dropped) listener.onText(text)
    }

    /** The connection drops. */
    fun drop() {
        if (cancelled || dropped) return
        dropped = true
        listener.onClosed()
    }
}

/** The seam's test double: no socket is ever opened. */
internal class FakeFreshTransport : FreshSocketTransport {
    val opened = mutableListOf<FakeFreshSocket>()
    val latest: FakeFreshSocket? get() = opened.lastOrNull()

    override fun open(url: String, listener: FreshSocketListener): FreshSocket =
        FakeFreshSocket(url, listener).also { opened.add(it) }
}

/**
 * Port of iOS `FreshRelayClientTests` (zapcooking_ios #202), case for case,
 * against a scripted transport on virtual time, followed by the Android
 * riders (ordering, tail flow, cancellation, frame JSON, close).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshRelayClientTest {

    // Fixtures

    private class Author {
        private val keys = Keys.generate()
        fun event(
            createdAt: Long,
            kind: Int = 1,
            content: String? = null,
            tags: List<List<String>> = emptyList(),
        ): NostrEvent = NostrEvent.create(
            privkey = keys.privkey, pubkey = keys.pubkey, kind = kind,
            content = content ?: "post at $createdAt ${UUID.randomUUID()}",
            tags = tags, createdAt = createdAt,
        )
    }

    private class Req(val subId: String, val filter: JsonObject) {
        val since: Long? get() = filter["since"]?.jsonPrimitive?.long
        val until: Long? get() = filter["until"]?.jsonPrimitive?.long
        val limit: Int? get() = filter["limit"]?.jsonPrimitive?.int
        val kinds: List<Int>? get() = filter["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }
        val search: String? get() = filter["search"]?.jsonPrimitive?.content
        val tTags: List<String>? get() = filter["#t"]?.jsonArray?.map { it.jsonPrimitive.content }
    }

    /** Records one tail flow. */
    private class TailBox {
        val eventIds = mutableListOf<String>()
        var lostCount = 0
        val revivedSince = mutableListOf<Long>()
        val ended = mutableListOf<FreshOutcome>()

        fun add(update: FreshTailUpdate) {
            when (update) {
                is FreshTailUpdate.Event -> eventIds.add(update.event.id)
                FreshTailUpdate.Lost -> lostCount++
                is FreshTailUpdate.Revived -> revivedSince.add(update.since)
                is FreshTailUpdate.Ended -> ended.add(update.outcome)
            }
        }
    }

    private fun TestScope.makeClient(
        transport: FakeFreshTransport,
        timeoutMs: Long = 5_000,
        now: Long = NOW,
    ) = FreshRelayClient(
        transport = transport, now = { now }, silenceTimeoutMs = timeoutMs,
        dispatcher = StandardTestDispatcher(testScheduler),
        monotonicMs = { testScheduler.currentTime },
    )

    private fun TestScope.record(client: FreshRelayClient, since: Long, box: TailBox): Job =
        launch { client.liveTail(since).collect { box.add(it) } }

    private fun reqs(socket: FakeFreshSocket?): List<Req> = (socket?.sent ?: emptyList()).mapNotNull { frame ->
        val array = Json.parseToJsonElement(frame) as? JsonArray ?: return@mapNotNull null
        if (array.size != 3 || array[0].jsonPrimitive.content != "REQ") return@mapNotNull null
        Req(array[1].jsonPrimitive.content, array[2].jsonObject)
    }

    private fun allSent(transport: FakeFreshTransport): List<String> = transport.opened.flatMap { it.sent }

    /** The [n]th REQ (1-based) on the transport's latest socket, once everything runnable has run. */
    private fun TestScope.awaitReq(transport: FakeFreshTransport, n: Int = 1): Req {
        runCurrent()
        val sent = reqs(transport.latest)
        assertTrue("REQ #$n never sent", sent.size >= n)
        return sent[n - 1]
    }

    private fun eventFrame(subId: String, event: NostrEvent) = """["EVENT","$subId",${event.toJson()}]"""
    private fun eose(subId: String) = """["EOSE","$subId"]"""
    private fun close(subId: String) = """["CLOSE","$subId"]"""
    private fun closed(subId: String, reason: String) = """["CLOSED","$subId","$reason"]"""

    private fun feed(socket: FakeFreshSocket?, subId: String, events: List<NostrEvent>, eose: Boolean = true) {
        for (event in events) socket?.feed(eventFrame(subId, event))
        if (eose) socket?.feed(eose(subId))
    }

    private fun challenge(socket: FakeFreshSocket?) {
        socket?.feed("""["AUTH","challenge-${UUID.randomUUID()}"]""")
    }

    private fun invalid(socket: FakeFreshSocket?, subId: String) {
        socket?.feed(closed(subId, "invalid: bad filter"))
    }

    /** [count] posts one second apart, newest first, ending at [newest]. */
    private fun posts(author: Author, newest: Long, count: Int): List<NostrEvent> =
        (0 until count).map { author.event(newest - it) }

    private companion object {
        const val NOW = 1_800_000_000L
        const val FLOOR = NOW - FreshRelayClient.FREE_WINDOW_SECONDS
        const val AUTH_REQUIRED_PAGE =
            "auth-required: posts older than 14 days are for Zap Cooking members; log in with NIP-42"
        const val AUTH_REQUIRED_TOPIC =
            "auth-required: topic: search beyond the preview is for Zap Cooking members; log in with NIP-42"
        val MAIN_FILES = listOf("FreshRelayClient.kt", "FreshSocketTransport.kt")
    }

    // Isolation

    /** connects only to wss://feed.zap.cooking and reuses one socket across requests */
    @Test
    fun oneSocketToTheFeedRelay() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val first = async { client.feedPage() }
        val req1 = awaitReq(transport)
        feed(transport.latest, req1.subId, emptyList())
        first.await()
        val second = async { client.topicPreview("pasta") }
        val req2 = awaitReq(transport, 2)
        feed(transport.latest, req2.subId, emptyList())
        second.await()
        assertEquals(1, transport.opened.size)
        assertEquals("wss://feed.zap.cooking", transport.latest?.url)
        assertEquals("wss://feed.zap.cooking", FeedRelay.URL)
    }

    /** the client sources name no signer, keypair, AUTH event or logger */
    @Test
    fun sourceHasNoSigner() {
        val dir = sequenceOf("src/main/kotlin/cooking/zap/app/fresh", "app/src/main/kotlin/cooking/zap/app/fresh")
            .map(::File).first { it.isDirectory }
        val sources = MAIN_FILES.associateWith { File(dir, it).readText() }
        for ((name, text) in sources) {
            for (forbidden in listOf(
                "Keypair", "Signer", "Keys.", "22242", "privkey", "ClientMessage", "RelayPool",
                "android.util.Log", "\"AUTH\"", "\"EVENT\",",
            )) {
                assertFalse("$name mentions $forbidden", text.contains(forbidden))
            }
            assertFalse("$name calls a logger", Regex("""\b(Log|Timber|DiagnosticLogger)\.\w+\(""").containsMatchIn(text))
            assertFalse("$name prints", Regex("""\bprintln\(""").containsMatchIn(text))
        }
        // The only frames it builds, and never by string concatenation.
        val client = sources.getValue("FreshRelayClient.kt")
        assertEquals(1, Regex("""add\("REQ"\)""").findAll(client).count())
        assertEquals(1, Regex("""add\("CLOSE"\)""").findAll(client).count())
        assertFalse(client.contains("[\\\"REQ"))
        assertFalse(client.contains("[\\\"CLOSE"))
    }

    /** no outgoing frame is AUTH or EVENT, even with a challenge before every answer and close */
    @Test
    fun neverSendsAuth() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()

        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        challenge(transport.latest)
        feed(transport.latest, req.subId, posts(author, NOW - 10, 3))
        page.await()

        val refused = async { client.feedPage(until = FLOOR) }
        val req2 = awaitReq(transport, 2)
        challenge(transport.latest)
        transport.latest?.feed(closed(req2.subId, AUTH_REQUIRED_PAGE))
        refused.await()

        val preview = async { client.topicPreview("pasta") }
        val req3 = awaitReq(transport, 3)
        challenge(transport.latest)
        transport.latest?.feed(closed(req3.subId, AUTH_REQUIRED_TOPIC))
        preview.await()

        val recorder = record(client, NOW, TailBox())
        awaitReq(transport, 4)
        challenge(transport.latest)
        client.stopTail()
        recorder.join()

        val sent = allSent(transport)
        assertTrue(sent.isNotEmpty())
        for (frame in sent) {
            assertFalse("sent $frame", frame.startsWith("[\"AUTH\""))
            assertTrue("sent $frame", frame.startsWith("[\"REQ\"") || frame.startsWith("[\"CLOSE\""))
        }
    }

    // Free window: feed pages

    /** row 1: first page asks kinds [1,30023,1068], since now−14d, limit 30; 30 events newest first */
    @Test
    fun firstPage() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val events = posts(Author(), NOW - 5, 30).shuffled()

        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        assertEquals(listOf(1, 30023, 1068), req.kinds)
        assertEquals(FLOOR, req.since)
        assertEquals(30, req.limit)
        assertNull(req.until)
        assertNull(req.filter["#t"])
        feed(transport.latest, req.subId, events)
        val answer = page.await()

        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(30, answer.events.size)
        assertEquals(answer.events.map { it.created_at }.sortedDescending(), answer.events.map { it.created_at })
        assertFalse(answer.isShortPage)
        assertEquals(NOW - 34, answer.oldestCreatedAt)
        assertEquals(close(req.subId), transport.latest?.sent?.last())
    }

    /** row 2: next page with inclusive until repeats the boundary post once; it is dropped */
    @Test
    fun inclusiveUntilDropsBoundaryRepeat() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val all = posts(Author(), NOW - 5, 59)
        val first = all.subList(0, 30)
        val second = all.subList(29, 59) // starts with the boundary post

        val page1 = async { client.feedPage() }
        val req1 = awaitReq(transport)
        feed(transport.latest, req1.subId, first)
        val answer1 = page1.await()
        val until = answer1.oldestCreatedAt!!

        val page2 = async { client.feedPage(until = until) }
        val req2 = awaitReq(transport, 2)
        assertEquals(until, req2.until)
        assertEquals(FLOOR, req2.since)
        assertEquals(listOf(1, 30023, 1068), req2.kinds)
        feed(transport.latest, req2.subId, second)
        val answer2 = page2.await()

        assertEquals(FreshOutcome.Answered, answer2.outcome)
        assertEquals(29, answer2.events.size)
        assertTrue(answer1.events.map { it.id }.intersect(answer2.events.map { it.id }.toSet()).isEmpty())
        assertFalse(answer2.isShortPage)
    }

    /** row 3: the last page is short; asking again returns only the boundary post, dropped */
    @Test
    fun lastPageIsShort() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val last = posts(Author(), FLOOR + 100, 7)

        val page = async { client.feedPage(until = FLOOR + 100) }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, last)
        val answer = page.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(7, answer.events.size)
        assertTrue(answer.isShortPage)

        val again = async { client.feedPage(until = answer.oldestCreatedAt!!) }
        val req2 = awaitReq(transport, 2)
        feed(transport.latest, req2.subId, listOf(last.last()))
        val answer2 = again.await()
        assertTrue(answer2.events.isEmpty())
        assertTrue(answer2.isShortPage)
    }

    /** row 4: until below the floor is Floor and sends nothing; until == floor is sent */
    @Test
    fun untilBelowFloorIsNotSent() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)

        val below = client.feedPage(until = FLOOR - 1)
        assertEquals(FreshOutcome.Floor, below.outcome)
        assertTrue(below.events.isEmpty())
        assertTrue(transport.opened.isEmpty())

        val at = async { client.feedPage(until = FLOOR) }
        val req = awaitReq(transport)
        assertEquals(FLOOR, req.until)
        feed(transport.latest, req.subId, emptyList())
        assertEquals(FreshOutcome.Answered, at.await().outcome)
    }

    /** row 4: CLOSED auth-required on a feed page (relay clock ahead) is Floor, sent once, never AUTH */
    @Test
    fun authRequiredOnAPageIsTheFloor() = runTest {
        val transport = FakeFreshTransport()
        // until is inside the device's window, but the relay's clock says the
        // floor has moved past it (a reader sat on the feed, or a slow clock).
        val client = makeClient(transport)
        val page = async { client.feedPage(until = FLOOR + 3) }
        val req = awaitReq(transport)
        challenge(transport.latest)
        transport.latest?.feed(closed(req.subId, AUTH_REQUIRED_PAGE))
        assertEquals(FreshOutcome.Floor, page.await().outcome)
        // Give a retry, if there were one, time to go out.
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, reqs(transport.latest).size)
        assertTrue("nothing to CLOSE after the relay's CLOSED", transport.latest!!.sent.none { it.startsWith("[\"CLOSE\"") })
    }

    /** rows 5–6: every feed request carries since (old long-form can't leak without it) */
    @Test
    fun everyFeedRequestCarriesSince() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        for ((index, until) in listOf(null, NOW - 100, FLOOR).withIndex()) {
            val page = async { client.feedPage(until = until) }
            val req = awaitReq(transport, index + 1)
            assertEquals(FLOOR, req.since)
            feed(transport.latest, req.subId, emptyList())
            page.await()
        }
    }

    /** a full page all already shown asks the boundary second at max, then steps past it */
    @Test
    fun stepsPastAFullBoundarySecond() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val second = NOW - 1000
        val sameSecond = (0 until 3).map { author.event(second) }

        // Page 1 shows all three.
        val page1 = async { client.feedPage(limit = 3) }
        val r1 = awaitReq(transport)
        feed(transport.latest, r1.subId, sameSecond)
        page1.await()

        // Page 2 (limit 3, until = that second): all seen → limit 500 → all
        // seen, but only 3 < 500 came back: the walk stops at the wide request.
        val page2 = async { client.feedPage(until = second, limit = 3) }
        val r2 = awaitReq(transport, 2)
        feed(transport.latest, r2.subId, sameSecond)
        val r3 = awaitReq(transport, 3)
        assertEquals(FreshRelayClient.MAX_LIMIT, r3.limit)
        assertEquals(second, r3.until)
        feed(transport.latest, r3.subId, sameSecond)
        val answer = page2.await()
        assertTrue(answer.events.isEmpty())
        assertTrue(answer.isShortPage)
        assertEquals(3, reqs(transport.latest).size)
    }

    // Topic preview

    /** preview: search topic:<slug>, since the floor, limit 5, no until */
    @Test
    fun previewRequest() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val preview = async { client.topicPreview("meat-seafood") }
        val req = awaitReq(transport)
        assertEquals("topic:meat-seafood", req.search)
        assertEquals(FLOOR, req.since)
        assertEquals(FreshRelayClient.PREVIEW_LIMIT, req.limit)
        assertNull(req.until)
        assertEquals(listOf(1, 30023, 1068), req.kinds)
        feed(transport.latest, req.subId, posts(Author(), NOW - 50, 5))
        val answer = preview.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(5, answer.events.size)
    }

    /** preview: a topic with nothing in the window is answered with no events */
    @Test
    fun previewEmpty() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val preview = async { client.topicPreview("canning") }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, emptyList())
        val answer = preview.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertTrue(answer.events.isEmpty())
    }

    /** preview: unknown slug → CLOSED invalid: is Invalid and is never sent again */
    @Test
    fun previewInvalidIsPermanent() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val first = async { client.topicPreview("no-such-topic") }
        val req = awaitReq(transport)
        transport.latest?.feed(closed(req.subId, "invalid: unknown topic no-such-topic"))
        assertEquals(FreshOutcome.Invalid("invalid: unknown topic no-such-topic"), first.await().outcome)

        val again = client.topicPreview("no-such-topic")
        assertTrue("expected Invalid, got ${again.outcome}", again.outcome is FreshOutcome.Invalid)
        assertEquals(1, reqs(transport.latest).size)

        // Other slugs are unaffected.
        val other = async { client.topicPreview("pasta") }
        val req2 = awaitReq(transport, 2)
        assertEquals("topic:pasta", req2.search)
        feed(transport.latest, req2.subId, emptyList())
        assertEquals(FreshOutcome.Answered, other.await().outcome)
    }

    /** preview: CLOSED auth-required (a preview can't page) is Floor */
    @Test
    fun previewAuthRequiredIsFloor() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val preview = async { client.topicPreview("pasta") }
        val req = awaitReq(transport)
        challenge(transport.latest)
        transport.latest?.feed(closed(req.subId, AUTH_REQUIRED_TOPIC))
        assertEquals(FreshOutcome.Floor, preview.await().outcome)
    }

    // Recipes (long-form at any age)

    /** recipes: kind 30023 with #t zapcooking/nostrcooking, no since, limit 500, paged until short */
    @Test
    fun recipesPageAtAnyAge() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val oldest = 1_694_000_000L // 2023-09, older than the floor
        val firstPage = (0 until 500).map { author.event(NOW - 10 - it * 1000L, kind = 30023) }
        val boundary = firstPage.last()
        val secondPage = listOf(boundary) + (0 until 119).map { author.event(oldest + it, kind = 30023) }

        val recipes = async { client.recipes() }
        val r1 = awaitReq(transport)
        assertEquals(listOf(30023), r1.kinds)
        assertEquals(listOf("zapcooking", "nostrcooking"), r1.tTags)
        assertNull(r1.since)
        assertNull(r1.until)
        assertEquals(500, r1.limit)
        feed(transport.latest, r1.subId, firstPage)
        val r2 = awaitReq(transport, 2)
        assertEquals(boundary.created_at, r2.until)
        assertNull(r2.since)
        assertEquals(listOf("zapcooking", "nostrcooking"), r2.tTags)
        feed(transport.latest, r2.subId, secondPage)
        val answer = recipes.await()

        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(619, answer.events.size)
        assertEquals(oldest, answer.events.last().created_at)
        assertEquals(2, reqs(transport.latest).size)
    }

    // Outcomes

    /** silence: TimedOut keeps the events that arrived */
    @Test
    fun timeoutKeepsPartialEvents() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport, timeoutMs = 300)
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, posts(Author(), NOW - 5, 12), eose = false)
        val answer = page.await()
        assertEquals(FreshOutcome.TimedOut, answer.outcome)
        assertEquals(12, answer.events.size)
        assertEquals(NOW - 16, answer.oldestCreatedAt)
        assertEquals(close(req.subId), transport.latest?.sent?.last())
    }

    /** silence: the timeout restarts with every event */
    @Test
    fun timeoutRestartsPerEvent() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport, timeoutMs = 400)
        val events = posts(Author(), NOW - 5, 4)
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        for (event in events) {
            advanceTimeBy(250)
            transport.latest?.feed(eventFrame(req.subId, event))
            runCurrent()
        }
        transport.latest?.feed(eose(req.subId))
        val answer = page.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(4, answer.events.size)
    }

    /** a dropped connection mid-page is Failed and keeps the events that arrived; the next request reconnects */
    @Test
    fun dropKeepsPartialEvents() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, posts(Author(), NOW - 5, 4), eose = false)
        advanceTimeBy(50)
        transport.latest?.drop()
        val answer = page.await()
        assertEquals(FreshOutcome.Failed("connection lost"), answer.outcome)
        assertEquals(4, answer.events.size)

        val next = async { client.feedPage(until = answer.oldestCreatedAt!!) }
        runCurrent()
        assertEquals(2, transport.opened.size)
        feed(transport.latest, reqs(transport.latest)[0].subId, emptyList())
        assertEquals(FreshOutcome.Answered, next.await().outcome)
    }

    /** CLOSED with any other reason is Failed with the reason */
    @Test
    fun otherClosedIsFailed() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        transport.latest?.feed(closed(req.subId, "error: shutting down"))
        assertEquals(FreshOutcome.Failed("error: shutting down"), page.await().outcome)
    }

    /** a bad id is dropped and counted */
    @Test
    fun badIdDropped() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val good = author.event(NOW - 5)
        val signed = author.event(NOW - 6)
        // Valid signature over the original id, content changed after signing.
        val tampered = signed.copy(content = signed.content + " (edited)")
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, listOf(good, tampered))
        val answer = page.await()
        assertEquals(listOf(good.id), answer.events.map { it.id })
        assertEquals(2, answer.received)
        assertEquals(1, answer.rejected)
    }

    /** a bad signature is dropped and counted */
    @Test
    fun badSigDropped() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val good = author.event(NOW - 5)
        val signed = author.event(NOW - 6)
        // The other key's signature over its own event: right id, wrong signature.
        val forged = signed.copy(sig = Author().event(NOW - 6).sig)
        val flipped = signed.sig.toCharArray().also { it[10] = if (it[10] == '0') '1' else '0' }
        val corrupt = signed.copy(sig = String(flipped))
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, listOf(good, forged, corrupt))
        val answer = page.await()
        assertEquals(listOf(good.id), answer.events.map { it.id })
        assertEquals(2, answer.rejected)
    }

    /** verifier: a real signed event with escapes and tags verifies */
    @Test
    fun verifierAcceptsRealEvent() {
        val event = Author().event(
            NOW, kind = 30023, content = "line\n\"quoted\"\t\\ é 🍝",
            tags = listOf(listOf("d", "pasta"), listOf("t", "zapcooking")),
        )
        assertTrue(FreshEventVerifier.isValid(event))
        val roundTrip = FreshRelayClient.parseEvent(Json.parseToJsonElement(event.toJson()).jsonObject)
        assertEquals(event, roundTrip)
        assertTrue(FreshEventVerifier.isValid(roundTrip!!))
    }

    // Live tail

    /** tail: subscribes from since with the feed kinds; delivers new posts once, skipping ones on a page */
    @Test
    fun tailDeliversOnce() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val onPage = author.event(NOW - 5)

        val page = async { client.feedPage() }
        val r1 = awaitReq(transport)
        feed(transport.latest, r1.subId, listOf(onPage))
        page.await()

        val box = TailBox()
        val recorder = record(client, NOW, box)
        val tailReq = awaitReq(transport, 2)
        assertEquals(NOW, tailReq.since)
        assertEquals(listOf(1, 30023, 1068), tailReq.kinds)
        assertNull(tailReq.until)
        transport.latest?.feed(eose(tailReq.subId))
        val fresh = author.event(NOW + 2)
        feed(transport.latest, tailReq.subId, listOf(onPage, fresh, fresh), eose = false)

        runCurrent()
        assertEquals(listOf(fresh.id), box.eventIds)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(fresh.id), box.eventIds)
        client.stopTail()
        recorder.join()
        assertEquals(close(tailReq.subId), transport.latest?.sent?.last())
    }

    /** tail: lost on a dropped socket, revived from the foreground hook with an overlap, de-duplicated by id */
    @Test
    fun tailLostAndRevivedWithOverlap() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()

        val box = TailBox()
        val recorder = record(client, NOW - 1200, box)
        val req = awaitReq(transport)
        transport.latest?.feed(eose(req.subId))
        val newest = author.event(NOW - 600)
        feed(transport.latest, req.subId, listOf(newest), eose = false)
        runCurrent()
        assertEquals(listOf(newest.id), box.eventIds)

        transport.latest?.drop()
        runCurrent()
        assertEquals(1, box.lostCount)
        assertTrue(client.tailIsLost())

        client.foregrounded()
        runCurrent()
        assertEquals(2, transport.opened.size)
        val resumed = reqs(transport.latest)[0]
        val expectedSince = NOW - 600 - FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS
        assertEquals(expectedSince, resumed.since)
        assertEquals(listOf(expectedSince), box.revivedSince)
        assertFalse(client.tailIsLost())

        // The relay re-sends what was already shown, plus a post admitted late:
        // created 827 s before the newest one seen (probe 2026-11-10).
        val late = author.event(NOW - 600 - 827)
        feed(transport.latest, resumed.subId, listOf(newest, late))
        runCurrent()
        assertEquals(listOf(newest.id, late.id), box.eventIds)

        client.stopTail()
        recorder.join()
    }

    /** tail: a drop right after coming to the foreground revives at once, once */
    @Test
    fun tailDropJustAfterForegroundRevives() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)

        val box = TailBox()
        val recorder = record(client, NOW, box)
        awaitReq(transport)
        // Foreground while the tail still looks healthy: nothing to revive yet.
        client.foregrounded()
        assertEquals(1, transport.opened.size)
        // The background socket's death is reported after resume.
        transport.latest?.drop()
        runCurrent()
        assertEquals(2, transport.opened.size)
        assertEquals(1, reqs(transport.latest).size)
        assertEquals(1, box.lostCount)
        assertEquals(1, box.revivedSince.size)
        // A second drop is not auto-revived (no loop on a relay that can't open).
        transport.latest?.drop()
        runCurrent()
        assertEquals(2, box.lostCount)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, transport.opened.size)
        client.stopTail()
        recorder.join()
    }

    /** tail: revive does nothing while healthy or after stop; the foreground hook is inert then */
    @Test
    fun reviveIsInertWhenHealthyOrStopped() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val recorder = record(client, NOW, TailBox())
        awaitReq(transport)
        assertFalse(client.reviveTail())
        client.stopTail()
        recorder.join()
        transport.latest?.drop()
        client.foregrounded()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(client.reviveTail())
        assertEquals(1, reqs(transport.latest).size)
        assertEquals(1, transport.opened.size)
    }

    /** tail: since older than the floor is clamped to it */
    @Test
    fun tailSinceClampedToFloor() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val recorder = record(client, FLOOR - 5000, TailBox())
        assertEquals(FLOOR, awaitReq(transport).since)
        client.stopTail()
        recorder.join()
    }

    /** tail: CLOSED auth-required ends it as Floor, with no retry and no AUTH */
    @Test
    fun tailRefusedIsEnded() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val box = TailBox()
        val recorder = record(client, NOW, box)
        val req = awaitReq(transport)
        challenge(transport.latest)
        transport.latest?.feed(closed(req.subId, "auth-required: log in"))
        recorder.join()
        assertEquals(listOf<FreshOutcome>(FreshOutcome.Floor), box.ended)
        assertFalse(client.reviveTail())
        assertEquals(1, reqs(transport.latest).size)
        assertTrue(transport.latest!!.sent.none { it.startsWith("[\"AUTH\"") })
    }

    // Review rulings (iOS, 2026-11-10)

    /** invalid: on a feed page is reported, and the next feed page is sent again */
    @Test
    fun feedPageInvalidIsRetryable() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val first = async { client.feedPage() }
        val req = awaitReq(transport)
        invalid(transport.latest, req.subId)
        assertEquals(FreshOutcome.Invalid("invalid: bad filter"), first.await().outcome)

        val second = async { client.feedPage() }
        val req2 = awaitReq(transport, 2)
        assertNotEquals(req.subId, req2.subId)
        assertEquals(FLOOR, req2.since)
        val page = posts(Author(), NOW - 10, 3)
        feed(transport.latest, req2.subId, page)
        val answer = second.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(page.map { it.id }, answer.events.map { it.id })
    }

    /** invalid: on a recipes page is reported, and recipes are asked again */
    @Test
    fun recipesInvalidIsRetryable() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val first = async { client.recipes() }
        val req = awaitReq(transport)
        invalid(transport.latest, req.subId)
        assertTrue(first.await().outcome is FreshOutcome.Invalid)

        val second = async { client.recipes() }
        val req2 = awaitReq(transport, 2)
        assertEquals(FreshRelayClient.RECIPE_TAGS, req2.tTags)
        feed(transport.latest, req2.subId, emptyList())
        assertEquals(FreshOutcome.Answered, second.await().outcome)
    }

    /** invalid: on the tail ends it, and a new tail is sent again */
    @Test
    fun tailInvalidIsRetryable() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val box = TailBox()
        val recorder = record(client, NOW, box)
        val req = awaitReq(transport)
        invalid(transport.latest, req.subId)
        recorder.join()
        assertEquals(listOf<FreshOutcome>(FreshOutcome.Invalid("invalid: bad filter")), box.ended)

        val box2 = TailBox()
        val recorder2 = record(client, NOW, box2)
        val req2 = awaitReq(transport, 2)
        assertEquals(NOW, req2.since)
        assertTrue(box2.ended.isEmpty())
        client.stopTail()
        recorder2.join()
    }

    /** tail: a future-dated post can't move the resume point past now + 30 s */
    @Test
    fun tailNewestIsClampedToSkew() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val box = TailBox()
        val recorder = record(client, NOW - 1200, box)
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, listOf(author.event(NOW + 86_400), author.event(NOW - 100)), eose = false)
        runCurrent()
        assertEquals(2, box.eventIds.size)

        transport.latest?.drop()
        runCurrent()
        assertEquals(1, box.lostCount)
        client.foregrounded()
        runCurrent()
        assertEquals(2, transport.opened.size)
        val expected = NOW + FreshRelayClient.FUTURE_SKEW_SECONDS - FreshRelayClient.TAIL_RESUME_OVERLAP_SECONDS
        assertEquals(expected, reqs(transport.latest)[0].since)
        assertEquals(listOf(expected), box.revivedSince)
        client.stopTail()
        recorder.join()
    }

    /** numbers too large or fractional in an event from the relay drop the event; nothing throws */
    @Test
    fun outOfRangeNumbersAreDropped() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        val good = Author().event(NOW - 5)
        val base = good.toJson()
        val hugeTime = base.replace("\"created_at\":${good.created_at}", "\"created_at\":1e100")
        val hugeKind = base.replace("\"kind\":1", "\"kind\":1e100")
        val fraction = base.replace("\"created_at\":${good.created_at}", "\"created_at\":${good.created_at}.5")
        assertTrue(hugeTime != base && hugeKind != base && fraction != base)
        for (json in listOf(hugeTime, hugeKind, fraction)) {
            transport.latest?.feed("""["EVENT","${req.subId}",$json]""")
        }
        feed(transport.latest, req.subId, listOf(good))
        val answer = page.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(listOf(good.id), answer.events.map { it.id })
        assertEquals(3, answer.rejected)
    }

    // Android riders

    /** rider a: frames fed immediately before a drop are all applied, in order, and the answer keeps them */
    @Test
    fun framesBeforeADropAreAllApplied() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val page = async { client.feedPage(limit = 500) }
        val req = awaitReq(transport)
        val events = posts(Author(), NOW - 5, 200)
        // No yield between the frames and the drop: only the queue orders them.
        feed(transport.latest, req.subId, events, eose = false)
        transport.latest?.drop()
        val answer = page.await()
        assertEquals(FreshOutcome.Failed("connection lost"), answer.outcome)
        assertEquals(200, answer.received)
        assertEquals(events.map { it.id }, answer.events.map { it.id })
    }

    /** rider a: an answer queued before the drop wins; the drop does not turn it into a failure */
    @Test
    fun eoseQueuedBeforeADropIsAnAnswer() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        feed(transport.latest, req.subId, posts(Author(), NOW - 5, 3))
        transport.latest?.drop()
        val answer = page.await()
        assertEquals(FreshOutcome.Answered, answer.outcome)
        assertEquals(3, answer.events.size)
    }

    /** rider b: starting a new tail ends the previous one */
    @Test
    fun newTailEndsThePrevious() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val box1 = TailBox()
        val recorder1 = record(client, NOW, box1)
        val req1 = awaitReq(transport)

        val box2 = TailBox()
        val recorder2 = record(client, NOW - 60, box2)
        val req2 = awaitReq(transport, 2)
        assertTrue("the first tail's flow completed", recorder1.isCompleted)
        assertTrue(transport.latest!!.sent.contains(close(req1.subId)))
        assertEquals(NOW - 60, req2.since)

        // Late frames for the first tail's subscription go nowhere.
        val post = author.event(NOW + 1)
        feed(transport.latest, req1.subId, listOf(author.event(NOW + 2)), eose = false)
        feed(transport.latest, req2.subId, listOf(post), eose = false)
        runCurrent()
        assertTrue(box1.eventIds.isEmpty())
        assertEquals(listOf(post.id), box2.eventIds)
        client.stopTail()
        recorder2.join()
    }

    /** rider b: a slow collector loses nothing */
    @Test
    fun slowTailCollectorLosesNothing() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val received = mutableListOf<String>()
        val recorder = launch {
            client.liveTail(NOW).collect { update ->
                delay(1_000)
                if (update is FreshTailUpdate.Event) received.add(update.event.id)
            }
        }
        val req = awaitReq(transport)
        val events = posts(Author(), NOW + 300, 300).reversed()
        feed(transport.latest, req.subId, events, eose = false)
        advanceTimeBy(301_000)
        runCurrent()
        assertEquals(events.map { it.id }, received)
        client.stopTail()
        recorder.join()
    }

    /** rider b: cancelling the collector stops the tail and sends CLOSE for its subscription */
    @Test
    fun cancellingTheTailCollectorClosesItsSubscription() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val box = TailBox()
        val recorder = record(client, NOW, box)
        val req = awaitReq(transport)
        recorder.cancel()
        recorder.join()
        runCurrent()
        assertEquals(close(req.subId), transport.latest?.sent?.last())
        assertFalse(client.tailIsLost())
        // Stopped for good: a drop is not a lost tail, and nothing revives.
        transport.latest?.drop()
        runCurrent()
        assertFalse(client.reviveTail())
        assertEquals(0, box.lostCount)
        assertEquals(1, transport.opened.size)
    }

    /** rider c: a cancelled caller closes its subscription, leaves no timer, and its late frames are ignored */
    @Test
    fun cancelledRequestIsClosedAndForgotten() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val author = Author()
        val page = async { client.feedPage() }
        val req = awaitReq(transport)
        val late = author.event(NOW - 5)
        page.cancel()
        page.join()
        assertTrue(page.isCancelled)
        assertEquals(close(req.subId), transport.latest?.sent?.last())

        // Removed from the pending map: no silence timer is left to fire...
        val before = currentTime
        advanceUntilIdle()
        assertEquals(before, currentTime)
        // ...and its late frames settle nothing and send nothing.
        val sentBefore = transport.latest!!.sent.size
        feed(transport.latest, req.subId, listOf(late))
        runCurrent()
        assertEquals(sentBefore, transport.latest!!.sent.size)

        // The late post was not marked seen, and the socket is still good.
        val next = async { client.feedPage() }
        val req2 = awaitReq(transport, 2)
        feed(transport.latest, req2.subId, listOf(late))
        assertEquals(listOf(late.id), next.await().events.map { it.id })
        assertEquals(1, transport.opened.size)
    }

    /** rider d: a slug with a quote and a backslash produces valid JSON whose search field round-trips */
    @Test
    fun framesAreBuiltAsJson() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val slug = "pa\"s\\ta\n  🍝"
        val preview = async { client.topicPreview(slug) }
        runCurrent()
        val frame = Json.parseToJsonElement(transport.latest!!.sent.single()).jsonArray
        assertEquals(3, frame.size)
        assertEquals("REQ", frame[0].jsonPrimitive.content)
        val subId = frame[1].jsonPrimitive.content
        assertEquals("topic:$slug", frame[2].jsonObject["search"]?.jsonPrimitive?.content)
        feed(transport.latest, subId, emptyList())
        assertEquals(FreshOutcome.Answered, preview.await().outcome)
        val closeFrame = Json.parseToJsonElement(transport.latest!!.sent.last()).jsonArray
        assertEquals(listOf("CLOSE", subId), closeFrame.map { it.jsonPrimitive.content })
    }

    /** close(): cancels the socket, fails what was pending with what arrived, and leaves no timer */
    @Test
    fun closeLeavesNothingPending() = runTest {
        val transport = FakeFreshTransport()
        val client = makeClient(transport)
        val box = TailBox()
        val recorder = record(client, NOW, box)
        val tailReq = awaitReq(transport)
        val page = async { client.feedPage() }
        val req = awaitReq(transport, 2)
        feed(transport.latest, req.subId, posts(Author(), NOW - 5, 2), eose = false)
        runCurrent()

        client.close()
        val answer = page.await()
        assertEquals(FreshOutcome.Failed("closed"), answer.outcome)
        assertEquals(2, answer.events.size)
        recorder.join()
        val socket = transport.latest!!
        assertTrue(socket.cancelled)
        assertEquals(close(tailReq.subId), socket.sent.last())

        val before = currentTime
        advanceUntilIdle()
        assertEquals("a timer was left running", before, currentTime)
        assertFalse(client.reviveTail())

        // The client is still usable: the next request opens a new socket.
        val next = async { client.feedPage() }
        runCurrent()
        assertEquals(2, transport.opened.size)
        feed(transport.latest, reqs(transport.latest)[0].subId, emptyList())
        assertEquals(FreshOutcome.Answered, next.await().outcome)
    }
}
