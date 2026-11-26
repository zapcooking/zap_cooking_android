package cooking.zap.app.lazarus

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.SignerRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one write path (spec 0.6.1-draft "Recover"), with a scripted relay
 * network and a fake signer: the pre-sign re-read (a newer version asks
 * again, an older copy doesn't, no answering write relay stops everything
 * short of the explicit override), dating after the reviewed version, the
 * author checks before and after signing, and success judged on the write
 * relays. Ported from zap.cooking web's publish suite.
 */
class LazarusPublisherTest {

    private val author = "a".repeat(64)
    private val now = 1_700_000_000L
    private val writeRelays = listOf("wss://write-a", "wss://write-b")

    private fun followList(id: String, createdAt: Long, count: Int) =
        testEvent(id, createdAt, pubkey = author, tags = followTags(count))

    // The version to restore, and a clobber dated an hour ahead of this clock
    private val healthy = followList("1", now - 86_400, 40)
    private val clobbered = followList("2", now + 3_600, 3)

    private val transport = FakeTransport()
    private var appList = LazarusUserRelays(read = emptyList(), write = writeRelays)
    private val engine = LazarusScanEngine(
        transport = transport,
        appRelayList = { appList },
        defaultRelays = listOf("wss://default"),
        verify = fakeVerify,
        backgroundScope = CoroutineScope(Dispatchers.Unconfined)
    )
    private val publisher = LazarusPublisher(engine, verify = fakeVerify, now = { now })
    private val signer = FakeSigner(author)
    private var active: String? = author

    /** The write relays hold [events] for the re-read (older or newer than the review, or none). */
    private fun writeRelaysHold(vararg events: NostrEvent) {
        transport.answer = { _, _ -> answered(*events) }
    }

    private fun restore(
        reviewedCurrent: NostrEvent?,
        chosen: NostrEvent = healthy,
        localCopy: LazarusLocalCopy? = null,
        allowUnconfirmed: Boolean = false,
        stages: MutableList<LazarusRestoreStage>? = null
    ) = runBlocking {
        publisher.restore(
            chosen = chosen,
            reviewedCurrent = reviewedCurrent,
            pubkey = author,
            signer = signer,
            activePubkey = { active },
            localCopy = localCopy,
            respondingRelays = emptyList(),
            allowUnconfirmed = allowUnconfirmed,
            onStage = { stages?.add(it) }
        )
    }

    @Test
    fun `restores over an older copy on the write relays, dated after the reviewed version`() {
        // The clobber was published elsewhere and the write relays still hold an
        // older version: no edit since the review
        writeRelaysHold(followList("3", now - 3_600, 38))
        val stages = mutableListOf<LazarusRestoreStage>()
        val result = restore(clobbered, stages = stages)
        assertTrue(result is LazarusPublisher.Result.Published)
        val event = (result as LazarusPublisher.Result.Published).report.event
        assertEquals(clobbered.created_at + 1, event.created_at)
        assertEquals(healthy.tags, event.tags)
        assertEquals(healthy.content, event.content)
        assertEquals(
            listOf(LazarusRestoreStage.CONFIRMING, LazarusRestoreStage.SIGNING, LazarusRestoreStage.PUBLISHING),
            stages
        )
    }

    @Test
    fun `asks again when a newer version appeared, then restores on the retry`() {
        val newer = followList("4", now + 7_200, 5)
        writeRelaysHold(newer)
        val changed = restore(clobbered)
        assertEquals(LazarusPublisher.Result.Changed(newer, writeRelays, confirmed = true), changed)
        assertEquals(0, signer.signed)

        // The retry passes the version the delta was recomputed against
        val retry = restore(newer)
        assertTrue(retry is LazarusPublisher.Result.Published)
        assertEquals(newer.created_at + 1, (retry as LazarusPublisher.Result.Published).report.event.created_at)
    }

    @Test
    fun `treats a version found when none was reviewed as a change`() {
        val found = followList("5", now - 60, 10)
        writeRelaysHold(found)
        assertTrue(restore(null) is LazarusPublisher.Result.Changed)
        assertEquals(0, signer.signed)
    }

    @Test
    fun `aborts before signing when no write relay answers the re-read`() {
        transport.answer = { url, _ -> if (url == "wss://write-a") failed() else timedOut() }
        assertEquals(LazarusPublisher.Result.Unconfirmed, restore(clobbered))
        assertEquals(0, signer.signed)
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `restores anyway under the explicit override, dated after the reviewed version`() {
        transport.answer = { _, _ -> failed() }
        val result = restore(clobbered, allowUnconfirmed = true)
        assertTrue(result is LazarusPublisher.Result.Published)
        assertEquals(clobbered.created_at + 1, (result as LazarusPublisher.Result.Published).report.event.created_at)
        assertEquals(1, signer.signed)
    }

    @Test
    fun `never lets the override through a newer version a failed relay sent`() {
        val newer = followList("6", now + 7_200, 5)
        // Sent a newer version, then failed before EOSE: still evidence of an edit
        transport.answer = { url, _ -> if (url == "wss://write-a") failed(newer) else failed() }
        val result = restore(clobbered, allowUnconfirmed = true)
        assertEquals(LazarusPublisher.Result.Changed(newer, listOf("wss://write-a"), confirmed = false), result)
        assertEquals(0, signer.signed)
    }

    @Test
    fun `asks again when the app's own copy holds a newer version`() {
        writeRelaysHold(clobbered)
        val local = followList("7", now + 7_200, 12)
        val result = restore(clobbered, localCopy = LazarusLocalCopy(local.created_at, local))
        // Known only from the app's copy: no relay had it
        assertEquals(LazarusPublisher.Result.Changed(local, emptyList(), confirmed = true), result)
        assertEquals(0, signer.signed)
    }

    @Test
    fun `stops when the app's copy is newer but its version isn't held`() {
        writeRelaysHold(clobbered)
        val result = restore(clobbered, localCopy = LazarusLocalCopy(now + 7_200))
        assertEquals(LazarusPublisher.Result.ChangedLocally(now + 7_200), result)
        assertEquals(0, signer.signed)
    }

    @Test
    fun `ignores a local copy that isn't a valid version of the list`() {
        writeRelaysHold(clobbered)
        val forged = followList("8", now + 9_000, 1).copy(sig = "forged")
        assertTrue(restore(clobbered, localCopy = LazarusLocalCopy(clobbered.created_at, forged)) is LazarusPublisher.Result.Published)
    }

    @Test
    fun `refuses before asking the signer when the active account isn't the list's`() {
        active = "b".repeat(64)
        assertTrue(restore(clobbered) is LazarusPublisher.Result.WrongAccount)
        assertEquals(0, signer.signed)
        assertTrue(transport.queries.isEmpty())
    }

    @Test
    fun `aborts when the account changes during the signer approval`() {
        writeRelaysHold(clobbered)
        signer.onSign = { active = "b".repeat(64) }
        assertTrue(restore(clobbered) is LazarusPublisher.Result.WrongAccount)
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `aborts when the signer signs as another account`() {
        writeRelaysHold(clobbered)
        signer.signAs = "c".repeat(64)
        assertTrue(restore(clobbered) is LazarusPublisher.Result.WrongAccount)
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `aborts when the signed event doesn't verify`() {
        writeRelaysHold(clobbered)
        signer.mutate = { it.copy(sig = "forged") }
        assertTrue(restore(clobbered) is LazarusPublisher.Result.WrongAccount)
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `publishes exactly what was reviewed, never an event the signer altered`() {
        writeRelaysHold(clobbered)
        signer.mutate = { it.copy(content = "tampered") }
        assertEquals(LazarusPublisher.Result.SignerMismatch, restore(clobbered))
        signer.mutate = { it.copy(created_at = it.created_at - 7_200) }
        assertEquals(LazarusPublisher.Result.SignerMismatch, restore(clobbered))
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `reports a declined signature without publishing`() {
        writeRelaysHold(clobbered)
        signer.fail = SignerRejectedException("declined")
        assertTrue(restore(clobbered) is LazarusPublisher.Result.SignFailed)
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `reports only the write relays that accepted the recovery`() {
        writeRelaysHold(clobbered)
        transport.accept = { url, _ -> LazarusPublishAnswer(accepted = url == "wss://write-b") }
        val result = restore(clobbered)
        assertTrue(result is LazarusPublisher.Result.Published)
        val report = (result as LazarusPublisher.Result.Published).report
        assertEquals(listOf("wss://write-b"), report.accepted)
        assertEquals(listOf("wss://write-a"), report.notAccepted.map { it.relayUrl })
    }

    @Test
    fun `reports a restore no write relay accepted as not accepted`() {
        writeRelaysHold(clobbered)
        transport.accept = { _, _ -> LazarusPublishAnswer(accepted = false) }
        val result = restore(clobbered)
        assertTrue(result is LazarusPublisher.Result.NotAccepted)
        assertEquals(1, signer.signed)
    }

    @Test
    fun `restores a relay list naming only dead relays through the override, judged on the restored relays`() {
        appList = LazarusUserRelays(read = emptyList(), write = listOf("wss://dead"))
        val healthyRelays = testEvent(
            "9", now - 86_400, kind = 10002, pubkey = author,
            tags = listOf(listOf("r", "wss://alive", "write"), listOf("r", "wss://inbox", "read"))
        )
        val deadRelays = testEvent("10", now + 3_600, kind = 10002, pubkey = author, tags = listOf(listOf("r", "wss://dead")))
        transport.answer = { _, _ -> failed() }
        transport.accept = { url, _ -> LazarusPublishAnswer(accepted = url != "wss://dead") }

        // The dead write relays can't confirm current, so only the override gets through
        assertEquals(LazarusPublisher.Result.Unconfirmed, restore(deadRelays, chosen = healthyRelays))
        val result = restore(deadRelays, chosen = healthyRelays, allowUnconfirmed = true)
        assertTrue(result is LazarusPublisher.Result.Published)
        val report = (result as LazarusPublisher.Result.Published).report
        assertTrue(report.judgedOnRestoredList)
        assertEquals(listOf("wss://alive"), report.writeRelays)
        assertEquals(listOf("wss://alive"), report.accepted)
        assertEquals(listOf("wss://dead"), report.bestEffort)
        assertEquals(deadRelays.created_at + 1, report.event.created_at)
    }
}
