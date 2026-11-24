package cooking.zap.app.api

import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.LocalSigner
import cooking.zap.app.nostr.Nip98HeaderCache
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The one live check of the scheduled-posts API, against the real
 * `https://zap.cooking`. **Skipped unless `ZAPCOOKING_SCHEDULE_LIVE=1`**,
 * so CI and ordinary local runs never touch the network:
 *
 * ```
 * ZAPCOOKING_SCHEDULE_LIVE=1 ./gradlew testZapstoreDebugUnitTest \
 *     --tests 'cooking.zap.app.api.ScheduleLiveTest' --rerun
 * ```
 *
 * With a fresh throwaway key it signs a kind 1 an hour out, schedules it
 * with `relays` omitted, finds it in `?status=pending`, cancels it, and
 * confirms it reads `cancelled`. **Nothing is ever published**: the note
 * is cancelled an hour before its time, and the key is discarded. It
 * spends one of the throwaway account's 30 creates per hour and nothing
 * of any real account's.
 *
 * On any failure the event id is printed so the row can be looked up
 * (and, if it was left pending, it belongs to a key nobody holds and to
 * an account nobody follows).
 */
class ScheduleLiveTest {

    @Test
    fun schedule_list_cancel_roundTrip() = runBlocking {
        assumeTrue(
            "set $ENV_FLAG=1 to run the live schedule check",
            System.getenv(ENV_FLAG) == "1",
        )

        val keys = Keys.generate()
        val signer = LocalSigner(keys.privkey, keys.pubkey)
        // Real clock, real signature verification, plain HTTP client.
        val api = ZapCookingApi(ZapCookingApi.DEFAULT_BASE_URL, OkHttpClient(), Nip98HeaderCache())
        val publishAt = System.currentTimeMillis() / 1000 + 3600
        println("[live] throwaway pubkey ${signer.pubkeyHex}")
        println("[live] publish_at $publishAt (one hour out)")

        val signResult = api.signForSchedule(
            signer,
            kind = 1,
            content = "Zap Cooking Android scheduler live check. This note is cancelled before it is ever published.",
            tags = emptyList(),
            publishAt = publishAt,
        )
        assertTrue("sign: $signResult", signResult is ScheduleSignResult.Signed)
        val event = (signResult as ScheduleSignResult.Signed).event
        println("[live] signed event id ${event.id}")

        try {
            val scheduled = api.scheduleEvent(signer, event, relays = emptyList())
            println("[live] POST   -> $scheduled")
            assertTrue("schedule: $scheduled", scheduled is ScheduleEventResult.Scheduled)
            scheduled as ScheduleEventResult.Scheduled
            assertEquals(event.id, scheduled.id)
            assertEquals(publishAt, scheduled.publishAt)
            assertEquals(ScheduleStatus.PENDING, scheduled.status)
            assertFalse(scheduled.alreadyScheduled)
            assertEquals("relays were omitted", emptyList<String>(), scheduled.relays)

            val pending = api.listScheduled(signer, ScheduleStatus.PENDING)
            assertTrue("list pending: $pending", pending is ScheduleListResult.Loaded)
            val pendingRow = (pending as ScheduleListResult.Loaded).items.firstOrNull { it.id == event.id }
            println("[live] GET ?status=pending   -> ${pending.items.size} row(s), ours: ${pendingRow?.status}")
            assertNotNull("the scheduled event is in the pending list", pendingRow)
            assertEquals(ScheduleStatus.PENDING, pendingRow!!.status)
            assertEquals("the stored event is byte-for-byte ours", event, pendingRow.event)

            val cancelled = api.cancelScheduled(signer, event.id)
            println("[live] DELETE -> $cancelled")
            assertEquals(ScheduleCancelResult.Cancelled(event.id, alreadyCancelled = false), cancelled)

            val after = api.listScheduled(signer, ScheduleStatus.CANCELLED)
            assertTrue("list cancelled: $after", after is ScheduleListResult.Loaded)
            val cancelledRow = (after as ScheduleListResult.Loaded).items.firstOrNull { it.id == event.id }
            println("[live] GET ?status=cancelled -> ${after.items.size} row(s), ours: ${cancelledRow?.status}")
            assertNotNull("the event is in the cancelled list", cancelledRow)
            assertEquals(ScheduleStatus.CANCELLED, cancelledRow!!.status)
            assertEquals("never sent", null, cancelledRow.sentAt)

            val stillPending = api.listScheduled(signer, ScheduleStatus.PENDING)
            assertTrue("list pending again: $stillPending", stillPending is ScheduleListResult.Loaded)
            val leftover = (stillPending as ScheduleListResult.Loaded).items.any { it.id == event.id }
            println("[live] GET ?status=pending   -> ours still pending: $leftover")
            assertFalse("the event is no longer pending", leftover)

            println("[live] OK: scheduled, listed, cancelled, reads cancelled. Nothing was published.")
        } catch (t: Throwable) {
            println("[live] FAILED for event id ${event.id} (pubkey ${signer.pubkeyHex}): $t")
            // Leave nothing pending behind if the failure came after the create.
            val cleanup = runCatching { api.cancelScheduled(signer, event.id) }
            println("[live] cleanup cancel -> ${cleanup.getOrNull() ?: cleanup.exceptionOrNull()}")
            throw t
        }
    }

    private companion object {
        const val ENV_FLAG = "ZAPCOOKING_SCHEDULE_LIVE"
    }
}
