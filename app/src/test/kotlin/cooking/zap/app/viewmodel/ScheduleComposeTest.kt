package cooking.zap.app.viewmodel

import cooking.zap.app.R
import cooking.zap.app.api.ScheduleFailure
import cooking.zap.app.api.ScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The composer's scheduling rules (ScheduleCompose.kt): picker bounds,
 * the PoW / schedule / private-reply constraints, the private-reply
 * backstop, the undo-countdown skip, and copy for every failure. The live
 * paths need Android; the DECISIONS are pure and pinned here.
 */
class ScheduleComposeTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toEpochSecond()

    private fun day(y: Int, mo: Int, d: Int): Long =
        LocalDate.of(y, mo, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    // --- picker bounds: 5 minutes to 90 days ---

    @Test
    fun `check allows exactly five minutes out and refuses anything sooner`() {
        val now = 1_800_000_000L
        assertEquals(ScheduleTime.Check.OK, ScheduleTime.check(now + 300, now))
        assertEquals(ScheduleTime.Check.TOO_SOON, ScheduleTime.check(now + 299, now))
        assertEquals(ScheduleTime.Check.TOO_SOON, ScheduleTime.check(now, now))
        assertEquals(ScheduleTime.Check.TOO_SOON, ScheduleTime.check(now - 86_400, now))
    }

    @Test
    fun `check refuses ninety days or more and allows just under`() {
        val now = 1_800_000_000L
        val ninetyDays = 90L * 86_400L
        assertEquals(ScheduleTime.Check.TOO_FAR, ScheduleTime.check(now + ninetyDays, now))
        assertEquals(ScheduleTime.Check.OK, ScheduleTime.check(now + ninetyDays - 1, now))
    }

    @Test
    fun `anything the picker allows clears the three-minute check at signing with room to spare`() {
        // Picker floor 300 s, signing floor 180 s: two minutes for the user to tap Schedule.
        assertTrue(ScheduleTime.PICKER_MIN_LEAD_SECONDS - 180 >= 120)
    }

    @Test
    fun `picker opens about an hour ahead on a whole minute and never in the past`() {
        for (now in listOf(1_800_000_000L, 1_800_000_001L, 1_800_000_059L, 1_800_000_060L)) {
            val opened = ScheduleTime.defaultTime(now)
            assertEquals(0L, opened % 60)
            assertTrue(opened >= now + 3600)
            assertTrue(opened < now + 3600 + 60)
            assertEquals(ScheduleTime.Check.OK, ScheduleTime.check(opened, now))
        }
    }

    @Test
    fun `today is selectable and yesterday is not`() {
        val now = at(utc, 2026, 11, 24, 12, 0)
        assertTrue(ScheduleTime.isSelectableDay(day(2026, 11, 24), now, utc))
        assertTrue(ScheduleTime.isSelectableDay(day(2026, 11, 25), now, utc))
        assertFalse(ScheduleTime.isSelectableDay(day(2026, 11, 23), now, utc))
        assertFalse(ScheduleTime.isSelectableDay(day(2025, 11, 24), now, utc))
    }

    @Test
    fun `today stops being selectable once it ends within five minutes`() {
        assertTrue(ScheduleTime.isSelectableDay(day(2026, 11, 24), at(utc, 2026, 11, 24, 23, 54, 59), utc))
        assertFalse(ScheduleTime.isSelectableDay(day(2026, 11, 24), at(utc, 2026, 11, 24, 23, 55, 0), utc))
        assertFalse(ScheduleTime.isSelectableDay(day(2026, 11, 24), at(utc, 2026, 11, 24, 23, 58), utc))
        // Tomorrow is, in both cases.
        assertTrue(ScheduleTime.isSelectableDay(day(2026, 11, 25), at(utc, 2026, 11, 24, 23, 58), utc))
    }

    @Test
    fun `days ninety or more days out are not selectable`() {
        val now = at(utc, 2026, 11, 24, 12, 0)
        // now + 90d = 2027-02-22 12:00: that day still has selectable minutes before noon.
        assertTrue(ScheduleTime.isSelectableDay(day(2027, 2, 22), now, utc))
        assertFalse(ScheduleTime.isSelectableDay(day(2027, 2, 23), now, utc))
        assertFalse(ScheduleTime.isSelectableDay(day(2027, 11, 24), now, utc))
    }

    @Test
    fun `selectable days follow the local calendar not UTC`() {
        // 21:00 on the 24th in New York is already the 25th in UTC.
        val now = at(newYork, 2026, 11, 24, 21, 0)
        assertTrue(ScheduleTime.isSelectableDay(day(2026, 11, 24), now, newYork))
        assertFalse(ScheduleTime.isSelectableDay(day(2026, 11, 23), now, newYork))
    }

    @Test
    fun `selectable years span now to ninety days out`() {
        val now = at(utc, 2026, 11, 24, 12, 0)
        assertFalse(ScheduleTime.isSelectableYear(2025, now, utc))
        assertTrue(ScheduleTime.isSelectableYear(2026, now, utc))
        assertTrue(ScheduleTime.isSelectableYear(2027, now, utc))
        assertFalse(ScheduleTime.isSelectableYear(2028, now, utc))
        val midYear = at(utc, 2026, 6, 1, 12, 0)
        assertFalse(ScheduleTime.isSelectableYear(2027, midYear, utc))
    }

    @Test
    fun `combine reads the picked day and wall-clock time in the local zone`() {
        assertEquals(at(utc, 2026, 11, 25, 18, 30), ScheduleTime.combine(day(2026, 11, 25), 18, 30, utc))
        assertEquals(at(newYork, 2026, 11, 25, 18, 30), ScheduleTime.combine(day(2026, 11, 25), 18, 30, newYork))
        assertNotEquals(
            ScheduleTime.combine(day(2026, 11, 25), 18, 30, utc),
            ScheduleTime.combine(day(2026, 11, 25), 18, 30, newYork),
        )
    }

    @Test
    fun `the day the picker opens on round-trips through combine`() {
        val now = at(newYork, 2026, 11, 24, 23, 30)
        val opened = ScheduleTime.defaultTime(now) // 00:30 on the 25th, local
        val openedDay = ScheduleTime.utcDayMillisOf(opened, newYork)
        assertEquals(day(2026, 11, 25), openedDay)
        assertTrue(ScheduleTime.isSelectableDay(openedDay, now, newYork))
        assertEquals(opened, ScheduleTime.combine(openedDay, 0, 30, newYork))
    }

    // --- proof of work and scheduling are mutually exclusive ---

    @Test
    fun `turning a schedule on switches PoW off and clearing it restores the earlier choice`() {
        val scheduled = ScheduleOptions(powEnabled = true).scheduleOn()
        assertTrue(scheduled.scheduleEnabled)
        assertFalse(scheduled.powEnabled)
        assertFalse(scheduled.powAvailable)

        val cleared = scheduled.withTimestamp(1_800_003_600L).scheduleOff()
        assertFalse(cleared.scheduleEnabled)
        assertNull(cleared.scheduleTimestamp)
        assertTrue(cleared.powEnabled)
        assertTrue(cleared.powAvailable)
        assertNull(cleared.powBeforeSchedule)
    }

    @Test
    fun `PoW that was off stays off after a schedule is cleared`() {
        val cleared = ScheduleOptions(powEnabled = false).scheduleOn().scheduleOff()
        assertFalse(cleared.powEnabled)
    }

    @Test
    fun `PoW cannot be toggled while a schedule is on`() {
        val scheduled = ScheduleOptions(powEnabled = true).scheduleOn()
        assertEquals(scheduled, scheduled.togglePow())
        assertFalse(scheduled.togglePow().powEnabled)
    }

    @Test
    fun `PoW toggles normally with no schedule`() {
        assertTrue(ScheduleOptions().togglePow().powEnabled)
        assertFalse(ScheduleOptions(powEnabled = true).togglePow().powEnabled)
    }

    @Test
    fun `a saved PoW preference arriving while scheduled is held back then restored`() {
        val scheduled = ScheduleOptions().scheduleOn().initPow(true)
        assertFalse(scheduled.powEnabled)
        assertTrue(scheduled.scheduleOff().powEnabled)
        // With no schedule it applies directly.
        assertTrue(ScheduleOptions().initPow(true).powEnabled)
    }

    @Test
    fun `backing out of the picker restores PoW`() {
        // Toggle on opens the picker; Cancel toggles off again with no time set.
        val backedOut = ScheduleOptions(powEnabled = true).toggleSchedule().toggleSchedule()
        assertEquals(ScheduleOptions(powEnabled = true), backedOut)
    }

    @Test
    fun `turning the schedule on twice does not lose the remembered PoW choice`() {
        val twice = ScheduleOptions(powEnabled = true).scheduleOn().scheduleOn()
        assertTrue(twice.scheduleOff().powEnabled)
    }

    @Test
    fun `a time only sticks while the schedule is on`() {
        assertNull(ScheduleOptions().withTimestamp(1_800_003_600L).scheduleTimestamp)
        assertEquals(1_800_003_600L, ScheduleOptions().scheduleOn().withTimestamp(1_800_003_600L).scheduleTimestamp)
    }

    // --- a private reply cannot be scheduled ---

    @Test
    fun `a schedule cannot be turned on while private reply is on`() {
        val private = ScheduleOptions(privateReply = true)
        assertFalse(private.scheduleAvailable)
        assertEquals(private, private.scheduleOn())
        assertEquals(private, private.toggleSchedule())
        assertNull(private.withTimestamp(1_800_003_600L).scheduleTimestamp)
    }

    @Test
    fun `turning private reply on clears a set schedule and restores PoW`() {
        val scheduled = ScheduleOptions(powEnabled = true).scheduleOn().withTimestamp(1_800_003_600L)
        val private = scheduled.withPrivateReply(true)
        assertTrue(private.privateReply)
        assertFalse(private.scheduleEnabled)
        assertNull(private.scheduleTimestamp)
        assertTrue(private.powEnabled)
        assertFalse(privateReplyBlockedBySchedule(private.privateReply, private.scheduleEnabled, private.scheduleTimestamp))
    }

    @Test
    fun `turning private reply off makes scheduling available again`() {
        val public = ScheduleOptions(privateReply = true).withPrivateReply(false)
        assertTrue(public.scheduleAvailable)
        assertTrue(public.scheduleOn().scheduleEnabled)
    }

    @Test
    fun `no sequence of option changes leaves a private reply with a schedule`() {
        val steps: List<(ScheduleOptions) -> ScheduleOptions> = listOf(
            { it.scheduleOn() },
            { it.scheduleOff() },
            { it.toggleSchedule() },
            { it.withTimestamp(1_800_003_600L) },
            { it.togglePow() },
            { it.initPow(true) },
            { it.withPrivateReply(true) },
            { it.withPrivateReply(false) },
        )
        // Every sequence of four steps from every start the UI can be in.
        var states = setOf(ScheduleOptions(), ScheduleOptions(powEnabled = true))
        repeat(4) {
            states = states.flatMap { state -> steps.map { step -> step(state) } }.toSet()
            for (state in states) {
                assertFalse(
                    "$state",
                    privateReplyBlockedBySchedule(state.privateReply, state.scheduleEnabled, state.scheduleTimestamp),
                )
                // And mining never coexists with a schedule.
                assertFalse("$state", state.scheduleEnabled && state.powEnabled)
            }
        }
    }

    // --- the publish-path backstop ---

    @Test
    fun `backstop refuses a private reply that still carries a schedule`() {
        assertTrue(privateReplyBlockedBySchedule(privateReply = true, scheduleEnabled = true, scheduleTimestamp = 1_800_003_600L))
        // Either half of the schedule state is enough: a time with the flag off, or the flag with no time.
        assertTrue(privateReplyBlockedBySchedule(privateReply = true, scheduleEnabled = false, scheduleTimestamp = 1_800_003_600L))
        assertTrue(privateReplyBlockedBySchedule(privateReply = true, scheduleEnabled = true, scheduleTimestamp = null))
    }

    @Test
    fun `backstop lets an unscheduled private reply and a scheduled public post through`() {
        assertFalse(privateReplyBlockedBySchedule(privateReply = true, scheduleEnabled = false, scheduleTimestamp = null))
        assertFalse(privateReplyBlockedBySchedule(privateReply = false, scheduleEnabled = true, scheduleTimestamp = 1_800_003_600L))
        assertFalse(privateReplyBlockedBySchedule(privateReply = false, scheduleEnabled = false, scheduleTimestamp = null))
    }

    // --- a scheduled post skips the undo countdown ---

    @Test
    fun `a scheduled post never waits out the undo countdown`() {
        assertFalse(useUndoCountdown(timerEnabled = true, timerSeconds = 10, scheduled = true))
        assertTrue(useUndoCountdown(timerEnabled = true, timerSeconds = 10, scheduled = false))
        assertFalse(useUndoCountdown(timerEnabled = false, timerSeconds = 10, scheduled = false))
        assertFalse(useUndoCountdown(timerEnabled = true, timerSeconds = 0, scheduled = false))
    }

    // --- the success message ---

    @Test
    fun `the success message gives the publish time in the user's zone`() {
        val publishAt = at(newYork, 2026, 11, 25, 18, 30)
        assertEquals("Nov 25, 2026 at 6:30 PM", formatScheduledTime(publishAt, newYork, Locale.US))
        // The same instant reads differently elsewhere: it is local time, not UTC.
        assertEquals("Nov 25, 2026 at 11:30 PM", formatScheduledTime(publishAt, utc, Locale.US))
    }

    @Test
    fun `the success message says scheduled and when and never posted`() {
        // No Android resources in the hermetic suite: read the string as shipped.
        val xml = File("src/main/res/values/strings.xml").readText()
        val text = Regex("""<string name="compose_post_scheduled">(.*?)</string>""").find(xml)!!.groupValues[1]
        assertTrue(text, text.contains("scheduled", ignoreCase = true))
        assertTrue(text, text.contains("%1\$s"))
        for (wrong in listOf("posted", "published", "sent")) {
            assertFalse(text, text.contains(wrong, ignoreCase = true))
        }
    }

    // --- copy ---

    private val everyFailure: List<ScheduleFailure> = listOf(
        ScheduleFailure.AuthFailed("event"),
        ScheduleFailure.BadRequest,
        ScheduleFailure.BadRelayMode,
        ScheduleFailure.BadRelays,
        ScheduleFailure.BadSignature,
        ScheduleFailure.PubkeyMismatch,
        ScheduleFailure.BadKind,
        ScheduleFailure.KindNotAllowedForRelayMode,
        ScheduleFailure.BadPublishTime,
        ScheduleFailure.TooLarge,
        ScheduleFailure.PollClosesBeforePublish,
        ScheduleFailure.AlreadyScheduled(ScheduleStatus.CANCELLED),
        ScheduleFailure.AlreadyScheduled(ScheduleStatus.FAILED),
        ScheduleFailure.AlreadyScheduled(null),
        ScheduleFailure.QuotaExceeded,
        ScheduleFailure.RateLimited,
        ScheduleFailure.BadStatus,
        ScheduleFailure.NotFound,
        ScheduleFailure.NotCancellable(ScheduleStatus.PUBLISHING),
        ScheduleFailure.NotCancellable(ScheduleStatus.SENT),
        ScheduleFailure.NotCancellable(ScheduleStatus.FAILED),
        ScheduleFailure.NotCancellable(null),
        ScheduleFailure.NotConfigured,
        ScheduleFailure.Internal,
        ScheduleFailure.SignedOut,
        ScheduleFailure.SignFailed,
        ScheduleFailure.SignerChangedEvent,
        ScheduleFailure.Network,
        ScheduleFailure.Unknown("brand_new_code"),
        ScheduleFailure.Unknown(null),
    )

    @Test
    fun `every failure has copy`() {
        for (failure in everyFailure) {
            assertNotEquals("$failure", 0, scheduleFailureMessageRes(failure))
        }
    }

    @Test
    fun `each failure the user can act on gets its own wording`() {
        assertEquals(R.string.schedule_error_auth_failed, scheduleFailureMessageRes(ScheduleFailure.AuthFailed("event")))
        assertEquals(R.string.schedule_error_bad_publish_time, scheduleFailureMessageRes(ScheduleFailure.BadPublishTime))
        assertEquals(R.string.schedule_error_too_large, scheduleFailureMessageRes(ScheduleFailure.TooLarge))
        assertEquals(R.string.schedule_error_poll_closes, scheduleFailureMessageRes(ScheduleFailure.PollClosesBeforePublish))
        assertEquals(R.string.schedule_error_quota_exceeded, scheduleFailureMessageRes(ScheduleFailure.QuotaExceeded))
        assertEquals(R.string.schedule_error_rate_limited, scheduleFailureMessageRes(ScheduleFailure.RateLimited))
        assertEquals(R.string.schedule_error_signer_changed, scheduleFailureMessageRes(ScheduleFailure.SignerChangedEvent))
        assertEquals(R.string.schedule_error_sign_failed, scheduleFailureMessageRes(ScheduleFailure.SignFailed))
        assertEquals(R.string.schedule_error_signed_out, scheduleFailureMessageRes(ScheduleFailure.SignedOut))
        assertEquals(R.string.schedule_error_network, scheduleFailureMessageRes(ScheduleFailure.Network))
        assertEquals(R.string.schedule_error_bad_signature, scheduleFailureMessageRes(ScheduleFailure.BadSignature))
        assertEquals(R.string.schedule_error_pubkey_mismatch, scheduleFailureMessageRes(ScheduleFailure.PubkeyMismatch))
        assertEquals(R.string.schedule_error_bad_kind, scheduleFailureMessageRes(ScheduleFailure.BadKind))
        assertEquals(R.string.schedule_error_not_configured, scheduleFailureMessageRes(ScheduleFailure.NotConfigured))
        assertEquals(
            R.string.schedule_error_already_scheduled,
            scheduleFailureMessageRes(ScheduleFailure.AlreadyScheduled(ScheduleStatus.CANCELLED)),
        )
    }

    @Test
    fun `client bugs and unknown codes read as a generic failure`() {
        assertEquals(R.string.schedule_error_generic, scheduleFailureMessageRes(ScheduleFailure.BadRequest))
        assertEquals(R.string.schedule_error_generic, scheduleFailureMessageRes(ScheduleFailure.BadRelayMode))
        assertEquals(R.string.schedule_error_internal, scheduleFailureMessageRes(ScheduleFailure.Unknown("brand_new_code")))
        assertEquals(R.string.schedule_error_internal, scheduleFailureMessageRes(ScheduleFailure.Internal))
    }

    @Test
    fun `not cancellable says what happened by status`() {
        assertEquals(
            R.string.schedule_error_not_cancellable_publishing,
            scheduleFailureMessageRes(ScheduleFailure.NotCancellable(ScheduleStatus.PUBLISHING)),
        )
        assertEquals(
            R.string.schedule_error_not_cancellable_sent,
            scheduleFailureMessageRes(ScheduleFailure.NotCancellable(ScheduleStatus.SENT)),
        )
        assertEquals(
            R.string.schedule_error_not_cancellable_failed,
            scheduleFailureMessageRes(ScheduleFailure.NotCancellable(ScheduleStatus.FAILED)),
        )
        assertEquals(
            R.string.schedule_error_not_cancellable,
            scheduleFailureMessageRes(ScheduleFailure.NotCancellable(null)),
        )
    }
}
