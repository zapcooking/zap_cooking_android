package cooking.zap.app.viewmodel

import cooking.zap.app.R
import cooking.zap.app.api.ScheduleCancelResult
import cooking.zap.app.api.ScheduleFailure
import cooking.zap.app.api.ScheduleListResult
import cooking.zap.app.api.ScheduleStatus
import cooking.zap.app.api.ScheduledItem
import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduled list's decisions (ScheduledList.kt): the two-call load,
 * row order, the preview for each schedulable kind, and what a cancel's
 * answer does to the row. The live path needs Android and the network;
 * the decisions are pure and pinned here.
 */
class ScheduledListTest {

    private val pubkey = "ab".repeat(32)

    private fun event(kind: Int = 1, content: String = "hello", tags: List<List<String>> = emptyList(), at: Long = 1_800_000_000L) =
        NostrEvent.createUnsigned(pubkey, kind, content, tags, at).withSignature("0".repeat(128))

    private fun item(
        id: String,
        publishAt: Long,
        status: ScheduleStatus = ScheduleStatus.PENDING,
        lastError: String? = null,
    ) = ScheduledItem(
        id = id.repeat(64).take(64),
        publishAt = publishAt,
        relays = emptyList(),
        status = status,
        attempts = 0,
        lastError = lastError,
        sentAt = null,
        event = event(at = publishAt),
    )

    private fun ids(rows: List<ScheduledItem>) = rows.map { it.id.take(1) }

    // --- order ---

    @Test
    fun `pending is soonest first then failed most recent first`() {
        val rows = orderScheduled(
            // Server order: newest publish time first.
            pending = listOf(item("c", 300), item("b", 200), item("a", 100)),
            failed = listOf(item("x", 50, ScheduleStatus.FAILED), item("y", 90, ScheduleStatus.FAILED)),
        )
        assertEquals(listOf("a", "b", "c", "y", "x"), ids(rows))
    }

    @Test
    fun `a row never appears twice`() {
        val rows = orderScheduled(listOf(item("a", 100)), listOf(item("a", 100, ScheduleStatus.FAILED)))
        assertEquals(1, rows.size)
    }

    // --- the two-call load ---

    @Test
    fun `asks for pending then failed and shows pending as soon as it returns`() = runBlocking {
        val asked = mutableListOf<ScheduleStatus>()
        val emitted = mutableListOf<List<String>>()
        var emittedBeforeFailedCall = -1

        val result = loadScheduledLists(
            previous = ScheduledLists(),
            list = { status ->
                if (status == ScheduleStatus.FAILED) emittedBeforeFailedCall = emitted.size
                asked += status
                when (status) {
                    ScheduleStatus.PENDING -> ScheduleListResult.Loaded(listOf(item("b", 200), item("a", 100)))
                    else -> ScheduleListResult.Loaded(listOf(item("x", 50, ScheduleStatus.FAILED)))
                }
            },
            emit = { emitted += ids(it.rows) },
        )

        assertEquals(listOf(ScheduleStatus.PENDING, ScheduleStatus.FAILED), asked)
        // Pending was on screen before the failed call was even made.
        assertEquals(1, emittedBeforeFailedCall)
        assertEquals(listOf(listOf("a", "b"), listOf("a", "b", "x")), emitted)
        assertEquals(listOf("a", "b", "x"), ids(result.rows))
        assertNull(result.failure)
    }

    @Test
    fun `if the failed call errors pending is shown anyway with no error`() = runBlocking {
        val result = loadScheduledLists(
            previous = ScheduledLists(),
            list = { status ->
                if (status == ScheduleStatus.PENDING) ScheduleListResult.Loaded(listOf(item("a", 100)))
                else ScheduleListResult.Failed(ScheduleFailure.Network)
            },
            emit = {},
        )
        assertEquals(listOf("a"), ids(result.rows))
        assertNull(result.failure)
    }

    @Test
    fun `if the failed call errors earlier failed rows are kept`() = runBlocking {
        val previous = ScheduledLists(failed = listOf(item("x", 50, ScheduleStatus.FAILED)))
        val result = loadScheduledLists(
            previous = previous,
            list = { status ->
                if (status == ScheduleStatus.PENDING) ScheduleListResult.Loaded(listOf(item("a", 100)))
                else ScheduleListResult.Failed(ScheduleFailure.Internal)
            },
            emit = {},
        )
        assertEquals(listOf("a", "x"), ids(result.rows))
    }

    @Test
    fun `if the pending call errors nothing is replaced and failed is not asked for`() = runBlocking {
        val asked = mutableListOf<ScheduleStatus>()
        val previous = ScheduledLists(pending = listOf(item("a", 100)), failed = listOf(item("x", 50, ScheduleStatus.FAILED)))
        val emitted = mutableListOf<ScheduledLists>()

        val result = loadScheduledLists(
            previous = previous,
            list = { status -> asked += status; ScheduleListResult.Failed(ScheduleFailure.Network) },
            emit = { emitted += it },
        )

        assertEquals(listOf(ScheduleStatus.PENDING), asked)
        assertEquals(listOf("a", "x"), ids(result.rows))
        assertEquals(ScheduleFailure.Network, result.failure)
        assertEquals(listOf(result), emitted)
    }

    @Test
    fun `a successful load clears an earlier error and replaces both lists`() = runBlocking {
        val previous = ScheduledLists(
            pending = listOf(item("a", 100)),
            failed = listOf(item("x", 50, ScheduleStatus.FAILED)),
            failure = ScheduleFailure.Network,
        )
        val result = loadScheduledLists(
            previous = previous,
            list = { ScheduleListResult.Loaded(emptyList()) },
            emit = {},
        )
        assertEquals(ScheduledLists(), result)
    }

    @Test
    fun `rows are trusted only for the status that was asked for`() = runBlocking {
        val result = loadScheduledLists(
            previous = ScheduledLists(),
            list = { status ->
                if (status == ScheduleStatus.PENDING) {
                    ScheduleListResult.Loaded(listOf(item("a", 100), item("s", 90, ScheduleStatus.SENT)))
                } else {
                    ScheduleListResult.Loaded(listOf(item("x", 50, ScheduleStatus.FAILED), item("c", 40, ScheduleStatus.CANCELLED)))
                }
            },
            emit = {},
        )
        assertEquals(listOf("a", "x"), ids(result.rows))
    }

    @Test
    fun `removing a row takes it out of whichever list holds it`() {
        val lists = ScheduledLists(pending = listOf(item("a", 100), item("b", 200)), failed = listOf(item("x", 50, ScheduleStatus.FAILED)))
        assertEquals(listOf("b", "x"), ids(lists.without("a".repeat(64)).rows))
        assertEquals(listOf("a", "b"), ids(lists.without("x".repeat(64)).rows))
    }

    // --- previews: every schedulable kind ---

    @Test
    fun `a note shows its text and leaves inline images to the text renderer`() {
        val preview = scheduledPreview(event(1, "  dinner https://img.example.com/a.jpg  "))
        assertEquals(ScheduledKind.NOTE, preview.kind)
        assertEquals("dinner https://img.example.com/a.jpg", preview.text)
        assertNull(preview.imageUrl)
        assertTrue(preview.pollOptions.isEmpty())
    }

    @Test
    fun `a kind 1 with an e tag is a reply`() {
        val preview = scheduledPreview(event(1, "agreed", listOf(listOf("e", "f".repeat(64), "", "root"), listOf("p", pubkey))))
        assertEquals(ScheduledKind.REPLY, preview.kind)
        assertEquals("agreed", preview.text)
    }

    @Test
    fun `a comment shows its text`() {
        val preview = scheduledPreview(event(1111, "nice recipe", listOf(listOf("E", "f".repeat(64)), listOf("K", "30023"))))
        assertEquals(ScheduledKind.COMMENT, preview.kind)
        assertEquals("nice recipe", preview.text)
    }

    @Test
    fun `a picture post shows its caption and first image`() {
        val preview = scheduledPreview(
            event(
                20, "sourdough",
                listOf(
                    listOf("imeta", "url https://img.example.com/1.jpg", "m image/jpeg", "dim 800x600"),
                    listOf("imeta", "url https://img.example.com/2.jpg", "m image/jpeg"),
                ),
            )
        )
        assertEquals(ScheduledKind.PICTURE, preview.kind)
        assertEquals("sourdough", preview.text)
        assertEquals("https://img.example.com/1.jpg", preview.imageUrl)
    }

    @Test
    fun `a picture post with no caption still has its image`() {
        val preview = scheduledPreview(event(20, "", listOf(listOf("imeta", "m image/jpeg", "url https://img.example.com/1.jpg"))))
        assertEquals("", preview.text)
        assertEquals("https://img.example.com/1.jpg", preview.imageUrl)
    }

    @Test
    fun `a video shows its poster frame when it has one and never the video file`() {
        val withPoster = scheduledPreview(
            event(21, "bread timelapse", listOf(listOf("imeta", "url https://v.example.com/a.mp4", "m video/mp4", "image https://v.example.com/a.jpg")))
        )
        assertEquals(ScheduledKind.VIDEO, withPoster.kind)
        assertEquals("https://v.example.com/a.jpg", withPoster.imageUrl)

        val without = scheduledPreview(event(22, "short", listOf(listOf("imeta", "url https://v.example.com/b.mp4", "m video/mp4"))))
        assertEquals(ScheduledKind.VIDEO, without.kind)
        assertNull(without.imageUrl)
        assertEquals("short", without.text)
    }

    @Test
    fun `a poll shows its question and options in order`() {
        val preview = scheduledPreview(
            event(
                1068, "Best flour?",
                listOf(listOf("option", "0", "Bread"), listOf("option", "1", "All-purpose"), listOf("polltype", "singlechoice"), listOf("relay", "wss://nos.lol")),
            )
        )
        assertEquals(ScheduledKind.POLL, preview.kind)
        assertEquals("Best flour?", preview.text)
        assertEquals(listOf("Bread", "All-purpose"), preview.pollOptions)
    }

    @Test
    fun `a zap poll shows its question and options`() {
        val preview = scheduledPreview(
            event(6969, "Tip the cook?", listOf(listOf("poll_option", "0", "Yes"), listOf("poll_option", "1", "No"), listOf("value_minimum", "21")))
        )
        assertEquals(ScheduledKind.ZAP_POLL, preview.kind)
        assertEquals(listOf("Yes", "No"), preview.pollOptions)
    }

    @Test
    fun `an article shows its title and cover not the whole body`() {
        val body = "# Heading\n" + "long text ".repeat(200)
        val titled = scheduledPreview(event(30023, body, listOf(listOf("d", "ramen"), listOf("title", "Ramen at home"), listOf("image", "https://img.example.com/c.jpg"))))
        assertEquals(ScheduledKind.ARTICLE, titled.kind)
        assertEquals("Ramen at home", titled.text)
        assertEquals("https://img.example.com/c.jpg", titled.imageUrl)

        val untitled = scheduledPreview(event(30023, body, listOf(listOf("d", "ramen"))))
        assertTrue(untitled.text.length <= 280)
        assertTrue(untitled.text.startsWith("# Heading"))
    }

    @Test
    fun `a kind this build does not know still shows its text`() {
        val preview = scheduledPreview(event(31337, "something new"))
        assertEquals(ScheduledKind.OTHER, preview.kind)
        assertEquals("something new", preview.text)
    }

    @Test
    fun `every schedulable kind has its own label`() {
        val kinds = listOf(1, 20, 21, 1068, 1111, 6969, 30023).map { scheduledPreview(event(it)).kind }
        assertEquals(kinds.size, kinds.toSet().size)
        assertFalse(ScheduledKind.OTHER in kinds)
        assertEquals(ScheduledKind.entries.size, ScheduledKind.entries.map { it.labelRes }.toSet().size)
    }

    // --- cancel ---

    private val id = "c".repeat(64)

    @Test
    fun `a cancelled post is removed and confirmed`() {
        assertEquals(
            CancelOutcome(removeRow = true, messageRes = R.string.scheduled_cancelled),
            cancelOutcome(ScheduleCancelResult.Cancelled(id, alreadyCancelled = false)),
        )
        assertEquals(
            CancelOutcome(removeRow = true, messageRes = R.string.scheduled_cancelled),
            cancelOutcome(ScheduleCancelResult.Cancelled(id, alreadyCancelled = true)),
        )
    }

    @Test
    fun `not cancellable says what really happened and never claims it was cancelled`() {
        val publishing = cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.NotCancellable(ScheduleStatus.PUBLISHING)))
        assertEquals(CancelOutcome(true, R.string.schedule_error_not_cancellable_publishing), publishing)

        val sent = cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.NotCancellable(ScheduleStatus.SENT)))
        assertEquals(CancelOutcome(true, R.string.schedule_error_not_cancellable_sent), sent)

        val failed = cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.NotCancellable(ScheduleStatus.FAILED)))
        assertEquals(CancelOutcome(true, R.string.schedule_error_not_cancellable_failed), failed)

        val unknown = cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.NotCancellable(null)))
        assertEquals(CancelOutcome(true, R.string.schedule_error_not_cancellable), unknown)

        for (outcome in listOf(publishing, sent, failed, unknown)) {
            assertFalse(outcome.messageRes == R.string.scheduled_cancelled)
        }
    }

    @Test
    fun `a post that no longer exists is removed`() {
        assertEquals(
            CancelOutcome(true, R.string.schedule_error_not_found),
            cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.NotFound)),
        )
    }

    @Test
    fun `when the cancel did not happen the row stays and says it is still scheduled`() {
        assertEquals(
            CancelOutcome(false, R.string.scheduled_cancel_failed_network),
            cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.Network)),
        )
        for (failure in listOf(ScheduleFailure.Internal, ScheduleFailure.Unknown("x"), ScheduleFailure.RateLimited, ScheduleFailure.BadRequest)) {
            assertEquals("$failure", CancelOutcome(false, R.string.scheduled_cancel_failed), cancelOutcome(ScheduleCancelResult.Failed(failure)))
        }
        assertEquals(
            CancelOutcome(false, R.string.schedule_error_auth_failed),
            cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.AuthFailed("event"))),
        )
        assertEquals(
            CancelOutcome(false, R.string.schedule_error_sign_failed),
            cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.SignFailed)),
        )
        assertEquals(
            CancelOutcome(false, R.string.schedule_error_signed_out),
            cancelOutcome(ScheduleCancelResult.Failed(ScheduleFailure.SignedOut)),
        )
    }

    // --- load errors ---

    @Test
    fun `a list that will not load never talks about scheduling a post`() {
        assertEquals(R.string.schedule_error_network, scheduleListFailureMessageRes(ScheduleFailure.Network))
        assertEquals(R.string.schedule_error_auth_failed, scheduleListFailureMessageRes(ScheduleFailure.AuthFailed("event")))
        assertEquals(R.string.schedule_error_signed_out, scheduleListFailureMessageRes(ScheduleFailure.SignedOut))
        assertEquals(R.string.schedule_error_not_configured, scheduleListFailureMessageRes(ScheduleFailure.NotConfigured))
        for (failure in listOf(ScheduleFailure.BadStatus, ScheduleFailure.Internal, ScheduleFailure.Unknown(null), ScheduleFailure.BadRequest)) {
            assertEquals("$failure", R.string.schedule_error_list_failed, scheduleListFailureMessageRes(failure))
        }
    }
}
