package cooking.zap.app.lazarus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay orchestration (spec 0.6.1-draft "Scan" and "Relay outcomes"),
 * against a scripted [FakeTransport]: the scan plan and the relay list —
 * found, missing or unknown — per-relay outcomes, validation of everything a
 * relay returns, paging, the retry of relays that didn't answer, the re-read
 * before a restore, and publishing judged on the write relays. Ported from
 * the reference implementation's relay-query vectors and zap.cooking web's
 * relay adapter suite.
 */
class LazarusEngineTest {

    private val pubkey = "test-pubkey"
    private val history = "wss://hist.nostr.land"

    private fun engine(
        transport: FakeTransport,
        appList: LazarusUserRelays? = null,
        maxConcurrent: Int = 12,
        appRelays: List<String> = emptyList()
    ) = LazarusScanEngine(
        transport = transport,
        appRelayList = { appList },
        appRelays = { appRelays },
        defaultRelays = listOf("wss://default"),
        verify = fakeVerify,
        maxConcurrent = maxConcurrent,
        backgroundScope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun writeOnly(vararg urls: String) = LazarusUserRelays(read = emptyList(), write = urls.toList())

    private fun follows(id: String, count: Int, createdAt: Long) =
        testEvent(id, createdAt, tags = followTags(count))

    // ---- the scan plan and the relay list ----

    @Test
    fun `scans every user relay, the defaults, and the archival set`() {
        val transport = FakeTransport()
        val engine = engine(
            transport,
            LazarusUserRelays(
                read = listOf("wss://hist.nostr.land/", "wss://r1/"),
                write = listOf("wss://w1/", "wss://w2/", "wss://w3/", "wss://w4/", "wss://w5/", "wss://w6/")
            )
        )
        val plan = runBlocking { engine.relayPlan(pubkey) }
        assertEquals(LazarusRelayListStatus.FOUND, plan.status)
        val relays = engine.scanRelays(plan)
        // Past the first five write relays, and read relays too
        assertTrue(relays.containsAll(listOf("wss://w6", "wss://r1", "wss://default")))
        Lazarus.ARCHIVAL_RELAYS.forEach { assertTrue(it in relays) }
        // A user relay that is also archival is scanned once
        assertEquals(1, relays.count { it == history })
        assertEquals(relays.size, relays.toSet().size)
        // The app's own copy is used as it is: no lookup
        assertTrue(transport.queries.isEmpty())
    }

    @Test
    fun `also scans the relays the app itself publishes on`() {
        val engine = engine(FakeTransport(), writeOnly("wss://w1"), appRelays = listOf("wss://pinned/", "wss://w1"))
        val relays = engine.scanRelays(runBlocking { engine.relayPlan(pubkey) })
        assertTrue("wss://pinned" in relays)
        assertEquals(1, relays.count { it == "wss://w1" })
        // They're scanned, but never taken for the user's write relays
        assertEquals(listOf("wss://w1"), runBlocking { engine.relayPlan(pubkey) }.write)
    }

    @Test
    fun `looks the relay list up itself when the app has none, taking the newest valid one`() {
        val transport = FakeTransport()
        val lists = listOf(
            testEvent("b1", 1000, kind = 10002, tags = listOf(listOf("r", "wss://old/", "write"))),
            testEvent("b2", 2000, kind = 10002, tags = listOf(listOf("r", "wss://new/", "write"), listOf("r", "wss://in", "read"))),
            // Newer, but forged or by someone else: never the user's list
            testEvent("b3", 3000, kind = 10002, tags = listOf(listOf("r", "wss://evil", "write")), sig = "forged"),
            testEvent("b4", 4000, kind = 10002, pubkey = "someone-else", tags = listOf(listOf("r", "wss://evil", "write")))
        )
        transport.answer = { _, filter -> if (filter.kinds == listOf(10002)) answered(*lists.toTypedArray()) else answered() }
        val plan = runBlocking { engine(transport).relayPlan(pubkey) }
        assertEquals(LazarusRelayListStatus.FOUND, plan.status)
        assertEquals(listOf("wss://new"), plan.write)
        assertEquals(listOf("wss://in"), plan.read)
        // Looked up on the default and archival relays
        assertTrue("wss://default" in transport.queriedUrls())
        assertTrue(history in transport.queriedUrls())
        assertTrue(transport.queries.all { it.second.limit == 1 && it.second.authors == listOf(pubkey) })
    }

    @Test
    fun `lets the defaults stand in when relays answered without a relay list`() {
        val transport = FakeTransport()
        val engine = engine(transport)
        val plan = runBlocking { engine.relayPlan(pubkey) }
        assertEquals(LazarusRelayListStatus.MISSING, plan.status)
        assertEquals(listOf("wss://default"), plan.write)
        assertTrue(history in engine.scanRelays(plan))
    }

    @Test
    fun `picks the lowest event id when two relay lists share a timestamp`() {
        // NIP-01: a replaceable write keeps the lowest id within one second,
        // so the live list is deterministic whichever relay answers first.
        val transport = FakeTransport()
        val lists = listOf(
            testEvent("aa", 1000, kind = 10002, tags = listOf(listOf("r", "wss://winner/", "write"))),
            testEvent("bb", 1000, kind = 10002, tags = listOf(listOf("r", "wss://loser/", "write")))
        )
        transport.answer = { _, filter -> if (filter.kinds == listOf(10002)) answered(*lists.toTypedArray()) else answered() }
        val plan = runBlocking { engine(transport).relayPlan(pubkey) }
        assertEquals(listOf("wss://winner"), plan.write)
    }

    @Test
    fun `counts a relay list that names no write relays as missing`() {
        val readOnlyCopy = runBlocking {
            engine(FakeTransport(), LazarusUserRelays(read = listOf("wss://inbox"), write = emptyList())).relayPlan(pubkey)
        }
        assertEquals(LazarusRelayListStatus.MISSING, readOnlyCopy.status)
        assertEquals(listOf("wss://default"), readOnlyCopy.write)
        assertEquals(listOf("wss://inbox"), readOnlyCopy.read)

        val transport = FakeTransport()
        transport.answer = { _, _ ->
            answered(testEvent("b1", 1000, kind = 10002, tags = listOf(listOf("r", "wss://inbox", "read"))))
        }
        val readOnlyLookup = runBlocking { engine(transport).relayPlan(pubkey) }
        assertEquals(LazarusRelayListStatus.MISSING, readOnlyLookup.status)
        assertEquals(listOf("wss://default"), readOnlyLookup.write)
    }

    @Test
    fun `never substitutes the defaults when no relay answered the lookup`() {
        val transport = FakeTransport()
        transport.answer = { _, _ -> failed() }
        val engine = engine(transport)
        val plan = runBlocking { engine.relayPlan(pubkey) }
        assertEquals(LazarusRelayListStatus.UNKNOWN, plan.status)
        assertTrue(plan.write.isEmpty())
        // The default and archival sets are still scanned
        val relays = engine.scanRelays(plan)
        assertTrue("wss://default" in relays)
        assertTrue(history in relays)
        // An unknown list isn't kept: the next scan asks again
        val asked = transport.queries.size
        runBlocking { engine.relayPlan(pubkey) }
        assertTrue(transport.queries.size > asked)
    }

    // ---- relay outcomes ----

    @Test
    fun `records how each relay ended, keeping versions sent before a failure`() {
        val transport = FakeTransport()
        val partial = follows("p1", 5, 1000)
        transport.answer = { url, _ ->
            when (url) {
                history -> failed(partial) // sent a version, then the connection dropped before EOSE
                "wss://w1" -> failed() // e.g. a NIP-01 CLOSED with auth-required:
                "wss://nostr.mom" -> timedOut()
                else -> answered()
            }
        }
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine(transport, writeOnly("wss://w1/"))) }
        assertEquals(listOf(partial.id), scan.candidates.map { it.event.id })
        assertEquals(LazarusRelayOutcome.FAILED, scan.relayOutcomes!![history])
        assertEquals(LazarusRelayOutcome.FAILED, scan.relayOutcomes!!["wss://w1"])
        assertEquals(LazarusRelayOutcome.TIMED_OUT, scan.relayOutcomes!!["wss://nostr.mom"])
        assertEquals(LazarusRelayOutcome.ANSWERED, scan.relayOutcomes!!["wss://nos.lol"])
        // The only write relay failed, so current is unconfirmed
        assertFalse(scan.currentConfirmed)
        assertEquals(listOf("wss://w1"), scan.writeRelays)
        assertFalse(lazarusScanReachedNoRelay(scan))
        assertEquals(setOf("wss://w1", history, "wss://nostr.mom"), lazarusUnansweredRelays(scan).toSet())
    }

    @Test
    fun `recommends nothing while no write relay answered`() {
        // A clear clobber on the history relay: 40 follows, then 3
        val full = follows("d1", 40, 1000)
        val clobbered = follows("d2", 3, 2000)
        fun scanWith(writeRelayAnswers: Boolean): LazarusScanResult {
            val transport = FakeTransport()
            transport.answer = { url, _ ->
                when {
                    url == "wss://w1" && !writeRelayAnswers -> failed()
                    url == history -> answered(full, clobbered)
                    else -> answered()
                }
            }
            return runBlocking { scanLazarusKind(3, pubkey, engine(transport, writeOnly("wss://w1"))) }
        }
        assertEquals(full.id, scanWith(writeRelayAnswers = true).recommended?.event?.id)
        val unconfirmed = scanWith(writeRelayAnswers = false)
        assertFalse(unconfirmed.currentConfirmed)
        assertNull(unconfirmed.recommended)
    }

    @Test
    fun `shows versions that arrived even when no relay answered`() {
        val transport = FakeTransport()
        val partial = follows("e1", 5, 1000)
        transport.answer = { url, _ -> if (url == history) failed(partial) else failed() }
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine(transport, writeOnly("wss://w1"))) }
        assertEquals(listOf(partial.id), scan.candidates.map { it.event.id })
        assertTrue(lazarusScanReachedNoRelay(scan))
    }

    @Test
    fun `fails a scan no relay answered, relay list lookup included`() {
        val transport = FakeTransport()
        transport.answer = { _, _ -> timedOut() }
        val error = runCatching { runBlocking { scanLazarusKind(3, pubkey, engine(transport)) } }.exceptionOrNull()
        assertTrue(error is LazarusScanFailedException)
    }

    // ---- untrusted relays ----

    @Test
    fun `counts only valid versions of the list from each relay`() {
        val transport = FakeTransport()
        val valid = follows("v1", 5, 1000)
        val foreign = follows("v2", 5, 1000).copy(pubkey = "someone-else")
        val wrongKind = follows("v3", 5, 1000).copy(kind = 10000)
        // A full page of forged versions must not count or move a cursor
        val forged = (0 until 50).map { i -> follows("f$i", 5, 900L + i).copy(sig = "forged") }
        transport.answer = { url, _ ->
            when (url) {
                history -> answered(valid, foreign, wrongKind)
                "wss://nos.lol" -> answered(*forged.toTypedArray())
                else -> answered()
            }
        }
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine(transport, writeOnly("wss://w1"))) }
        assertEquals(listOf(valid.id), scan.candidates.map { it.event.id })
        assertEquals(listOf(history), scan.respondingRelays)
        assertTrue(scan.olderCursors.isEmpty())
    }

    @Test
    fun `counts a relay that answered with only foreign events as having nothing`() {
        val transport = FakeTransport()
        transport.answer = serving("wss://hostile", listOf(follows("x1", 9, 1001).copy(pubkey = "someone-else")))
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine(transport, writeOnly("wss://hostile/"))) }
        assertTrue(scan.candidates.isEmpty())
        assertTrue(scan.respondingRelays.isEmpty())
        assertEquals(LazarusRelayOutcome.ANSWERED, scan.relayOutcomes!!["wss://hostile"])
        // It did answer, so it confirms that nothing newer sits there
        assertTrue(scan.currentConfirmed)
    }

    // ---- paging ----

    // 70 versions on one relay: more than one page
    private val longHistory = (0 until 70).map { i -> follows((i + 1).toString(16), 10, 1000L + i) }

    @Test
    fun `pages back from relays that filled a page`() {
        val transport = FakeTransport()
        transport.answer = serving(history, longHistory)
        val engine = engine(transport, writeOnly("wss://w1"))
        val profile = getLazarusKindProfile(3)!!
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine) }
        assertEquals(50, scan.candidates.size)
        assertEquals(mapOf(history to 1020L), scan.olderCursors)

        val older = runBlocking { loadOlderLazarusVersions(profile, scan, pubkey, engine) }
        assertEquals(70, older.candidates.size)
        assertTrue(older.olderCursors.isEmpty())
        assertEquals(scan.queriedRelays, older.queriedRelays)
        // The next page asked only that relay, from the inclusive cursor
        val paging = transport.queries.last()
        assertEquals(history, paging.first)
        assertEquals(1020L, paging.second.until)
    }

    @Test
    fun `stops paging a relay that ignores until`() {
        val transport = FakeTransport()
        transport.answer = serving(history, longHistory, honorUntil = false)
        val engine = engine(transport, writeOnly("wss://w1"))
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine) }
        val older = runBlocking { loadOlderLazarusVersions(getLazarusKindProfile(3)!!, scan, pubkey, engine) }
        assertEquals(50, older.candidates.size)
        assertTrue(older.olderCursors.isEmpty())
    }

    @Test
    fun `keeps paging open on a relay whose older page failed`() {
        val transport = FakeTransport()
        transport.answer = serving(history, longHistory)
        val engine = engine(transport, writeOnly("wss://w1"))
        val profile = getLazarusKindProfile(3)!!
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine) }

        // A failed page isn't the end of that relay's history
        transport.answer = { _, _ -> failed() }
        val stalled = runBlocking { loadOlderLazarusVersions(profile, scan, pubkey, engine) }
        assertEquals(50, stalled.candidates.size)
        assertEquals(mapOf(history to 1020L), stalled.olderCursors)

        transport.answer = serving(history, longHistory)
        val older = runBlocking { loadOlderLazarusVersions(profile, stalled, pubkey, engine) }
        assertEquals(70, older.candidates.size)
    }

    // ---- retry ----

    @Test
    fun `retries only the relays that didn't answer, and confirms current when a write relay does`() {
        val transport = FakeTransport()
        val full = follows("r1", 40, 1000)
        val clobbered = follows("r2", 3, 2000)
        transport.answer = { url, _ ->
            when (url) {
                "wss://w1" -> failed()
                history -> answered(clobbered)
                else -> answered()
            }
        }
        val engine = engine(transport, writeOnly("wss://w1"))
        val profile = getLazarusKindProfile(3)!!
        val scan = runBlocking { scanLazarusKind(3, pubkey, engine) }
        assertFalse(scan.currentConfirmed)
        assertEquals(listOf("wss://w1"), lazarusUnansweredRelays(scan))

        transport.queries.clear()
        transport.answer = { url, _ -> if (url == "wss://w1") answered(full, clobbered) else answered() }
        val retry = runBlocking {
            engine.retryRelays(3, pubkey, lazarusUnansweredRelays(scan), scan.writeRelays)
        }
        // Without repeating the whole scan
        assertEquals(listOf("wss://w1"), transport.queriedUrls())
        val merged = mergeLazarusRetry(profile, scan, retry)
        assertTrue(merged.currentConfirmed)
        assertEquals(full.id, merged.recommended?.event?.id)
        assertTrue(lazarusUnansweredRelays(merged).isEmpty())
    }

    // ---- the re-read before a restore ----

    @Test
    fun `reads every write relay before a restore, telling failed reads from empty ones`() {
        val transport = FakeTransport()
        val newer = follows("n1", 6, 2000)
        val foreign = follows("n2", 6, 3000).copy(pubkey = "someone-else")
        transport.answer = { url, _ ->
            when (url) {
                "wss://w1" -> answered(foreign)
                "wss://w2" -> answered(newer)
                else -> failed() // w3's connection fails before EOSE
            }
        }
        val read = runBlocking {
            engine(transport, writeOnly("wss://w1", "wss://w2", "wss://w3")).readCurrent(3, pubkey)
        }
        assertEquals(
            listOf(
                LazarusReadAnswer("wss://w1", emptyList(), answered = true),
                LazarusReadAnswer("wss://w2", listOf(newer), answered = true),
                LazarusReadAnswer("wss://w3", emptyList(), answered = false)
            ),
            read.answers
        )
        // Every write relay was asked for its newest version, not only the first to answer
        assertEquals(setOf("wss://w1", "wss://w2", "wss://w3"), transport.queriedUrls().toSet())
        assertTrue(transport.queries.all { it.second.limit == 1 && it.second.kinds == listOf(3) })
    }

    @Test
    fun `can't confirm current when the relay list is unknown`() {
        val transport = FakeTransport()
        transport.answer = { _, _ -> failed() }
        val read = runBlocking { engine(transport).readCurrent(3, pubkey) }
        assertTrue(read.answers.isEmpty())
        assertEquals(
            LazarusCurrentCheck.Unconfirmed,
            checkLazarusCurrent(follows("c1", 5, 2000), null, read.answers)
        )
    }

    // ---- publish ----

    @Test
    fun `judges success on the write relays and sends the rest as a best effort`() {
        val transport = FakeTransport()
        transport.accept = { url, _ ->
            if (url == "wss://w2") LazarusPublishAnswer(accepted = false, message = "blocked: spam")
            else LazarusPublishAnswer(accepted = true)
        }
        val event = follows("s1", 3, 5000)
        val report = runBlocking {
            engine(transport, writeOnly("wss://w1", "wss://w2")).publish(event, pubkey, listOf("wss://hist.nostr.land/", "wss://w1/"))
        }
        assertTrue(report.succeeded)
        assertEquals(listOf("wss://w1", "wss://w2"), report.writeRelays)
        assertEquals(listOf("wss://w1"), report.accepted)
        assertEquals(listOf(LazarusRelayRejection("wss://w2", "blocked: spam")), report.notAccepted)
        assertEquals(listOf(history), report.bestEffort)
        assertFalse(report.judgedOnRestoredList)
        assertFalse(report.writeRelaysAreDefaults)
        // The best-effort copy went out too
        assertTrue(history in transport.publishedUrls())
    }

    @Test
    fun `fails when no write relay accepts, without best-effort copies`() {
        val transport = FakeTransport()
        transport.accept = { _, _ -> LazarusPublishAnswer(accepted = false) }
        val report = runBlocking {
            engine(transport, writeOnly("wss://w1", "wss://w2")).publish(follows("s2", 3, 5000), pubkey, listOf(history))
        }
        assertFalse(report.succeeded)
        assertEquals(2, report.notAccepted.size)
        assertTrue(report.bestEffort.isEmpty())
        assertFalse(history in transport.publishedUrls())
    }

    @Test
    fun `judges a relay list restore on the write relays the restored version names`() {
        val transport = FakeTransport()
        // The current list names only a dead relay, the one the restore is meant to fix
        transport.accept = { url, _ -> LazarusPublishAnswer(accepted = url != "wss://dead") }
        val restoring = testEvent(
            "l1", 5000, kind = 10002,
            tags = listOf(listOf("r", "wss://alive/", "write"), listOf("r", "wss://both/"), listOf("r", "wss://inbox/", "read"))
        )
        val report = runBlocking {
            engine(transport, writeOnly("wss://dead/")).publish(restoring, pubkey, listOf(history))
        }
        assertTrue(report.succeeded)
        assertTrue(report.judgedOnRestoredList)
        assertEquals(listOf("wss://alive", "wss://both"), report.writeRelays)
        // The current write relays still get it as a best effort
        assertEquals(listOf("wss://dead", history), report.bestEffort)
    }

    @Test
    fun `uses the defaults when the restored relay list names no write relays`() {
        val transport = FakeTransport()
        val restoring = testEvent("l2", 5000, kind = 10002, tags = listOf(listOf("r", "wss://inbox/", "read")))
        val report = runBlocking { engine(transport, writeOnly("wss://dead/")).publish(restoring, pubkey, emptyList()) }
        assertEquals(listOf("wss://default"), report.writeRelays)
        assertTrue(report.writeRelaysAreDefaults)
        assertEquals(listOf("wss://dead"), report.bestEffort)
    }

    @Test
    fun `falls back to the defaults as write relays when the user has no relay list`() {
        val transport = FakeTransport()
        val report = runBlocking { engine(transport).publish(follows("s3", 3, 5000), pubkey, listOf("wss://a/")) }
        assertEquals(listOf("wss://default"), report.writeRelays)
        assertTrue(report.writeRelaysAreDefaults)
        assertEquals(listOf("wss://a"), report.bestEffort)
    }

    @Test
    fun `has no write relays to publish to when the relay list is unknown`() {
        val transport = FakeTransport()
        transport.answer = { _, _ -> failed() }
        val report = runBlocking { engine(transport).publish(follows("s4", 3, 5000), pubkey, listOf(history)) }
        assertTrue(report.writeRelays.isEmpty())
        assertFalse(report.succeeded)
        assertTrue(transport.published.isEmpty())
    }

    // ---- connections ----

    @Test
    fun `bounds concurrent relay requests`() {
        val transport = FakeTransport(delayMs = 20)
        val engine = engine(transport, writeOnly("wss://w1", "wss://w2", "wss://w3", "wss://w4"), maxConcurrent = 3)
        runBlocking { scanLazarusKind(3, pubkey, engine) }
        assertTrue(transport.maxInFlight.get() in 1..3)
    }

    @Test
    fun `releases the transport on close`() {
        val transport = FakeTransport()
        engine(transport).close()
        assertTrue(transport.closed)
    }

    @Test
    fun `normalizes relay URLs for dedup and outcome keys`() {
        assertEquals("wss://relay.example.com", normalizeLazarusRelayUrl(" WSS://Relay.Example.com/ "))
        assertEquals("wss://relay.example.com/Path", normalizeLazarusRelayUrl("wss://relay.example.com/Path/"))
        assertEquals("ws://localhost:7777", normalizeLazarusRelayUrl("ws://localhost:7777"))
        assertEquals("", normalizeLazarusRelayUrl("https://relay.example.com"))
        assertEquals("", normalizeLazarusRelayUrl("wss://"))
        assertEquals("", normalizeLazarusRelayUrl("not a relay"))
    }
}
