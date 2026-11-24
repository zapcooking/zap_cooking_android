package cooking.zap.app.viewmodel

import androidx.annotation.StringRes
import cooking.zap.app.R
import cooking.zap.app.api.ScheduleFailure
import cooking.zap.app.api.ScheduleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/*
 * The composer's scheduling rules, as pure functions so the hermetic JVM
 * suite can pin them. ComposeViewModel and ComposeScreen call these; the
 * contract behind them is docs/schedule-contract.md in zapcooking/frontend.
 */

/** What the schedule picker allows: 5 minutes to 90 days out. */
internal object ScheduleTime {
    /** Contract: clients enforce a 5-minute minimum in their pickers. */
    const val PICKER_MIN_LEAD_SECONDS = 5 * 60L

    /** Contract: strictly less than 90 days out. */
    const val MAX_LEAD_SECONDS = 90L * 86_400L

    /** Where the picker opens: about an hour ahead. */
    const val DEFAULT_LEAD_SECONDS = 3600L

    enum class Check { OK, TOO_SOON, TOO_FAR }

    fun check(publishAt: Long, nowSeconds: Long): Check = when {
        publishAt < nowSeconds + PICKER_MIN_LEAD_SECONDS -> Check.TOO_SOON
        publishAt >= nowSeconds + MAX_LEAD_SECONDS -> Check.TOO_FAR
        else -> Check.OK
    }

    /** The time the picker opens on: an hour from now, on a whole minute, never in the past. */
    fun defaultTime(nowSeconds: Long): Long =
        ((nowSeconds + DEFAULT_LEAD_SECONDS + 59) / 60) * 60

    /**
     * May this calendar day be picked? [utcDayMillis] is what Material's
     * DatePicker hands out: UTC midnight of the day shown. The day is
     * selectable when some minute of it, in [zone], is inside the allowed
     * window — so today is offered unless it ends within 5 minutes, and
     * past days and days 90 or more days out are not.
     */
    fun isSelectableDay(utcDayMillis: Long, nowSeconds: Long, zone: ZoneId): Boolean {
        val day = Instant.ofEpochMilli(utcDayMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toEpochSecond()
        val nextDayStart = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val earliest = nowSeconds + PICKER_MIN_LEAD_SECONDS
        val latestExclusive = nowSeconds + MAX_LEAD_SECONDS
        return nextDayStart > earliest && dayStart < latestExclusive
    }

    fun isSelectableYear(year: Int, nowSeconds: Long, zone: ZoneId): Boolean {
        val first = Instant.ofEpochSecond(nowSeconds).atZone(zone).year
        val last = Instant.ofEpochSecond(nowSeconds + MAX_LEAD_SECONDS).atZone(zone).year
        return year in first..last
    }

    /** UTC-midnight millis of the local calendar day [epochSeconds] falls on — the DatePicker's initial selection. */
    fun utcDayMillisOf(epochSeconds: Long, zone: ZoneId): Long =
        Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The picked day ([utcDayMillis], as the DatePicker reports it) at [hour]:[minute] wall-clock time in [zone], as unix seconds. */
    fun combine(utcDayMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val day: LocalDate = Instant.ofEpochMilli(utcDayMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return ZonedDateTime.of(day, LocalTime.of(hour, minute), zone).toEpochSecond()
    }
}

/**
 * The composer options that constrain each other, and the rules between
 * them:
 *
 *  - **Proof of work and scheduling are mutually exclusive.** Mining
 *    re-stamps `created_at`, which would move a scheduled post to "now".
 *    Turning a schedule on switches PoW off and remembers the earlier
 *    choice; clearing the schedule restores it. PoW cannot be toggled
 *    while a schedule is on.
 *  - **A private reply cannot be scheduled.** A schedule cannot be turned
 *    on while private reply is on, and turning private reply on clears a
 *    schedule that was set.
 */
internal data class ScheduleOptions(
    val scheduleEnabled: Boolean = false,
    val scheduleTimestamp: Long? = null,
    val powEnabled: Boolean = false,
    /** The PoW choice from before the schedule was turned on; null while no schedule is on. */
    val powBeforeSchedule: Boolean? = null,
    val privateReply: Boolean = false,
) {
    /** Whether the PoW toggle may be used. */
    val powAvailable: Boolean get() = !scheduleEnabled

    /** Whether the schedule toggle may be used. */
    val scheduleAvailable: Boolean get() = !privateReply

    fun scheduleOn(): ScheduleOptions =
        if (scheduleEnabled || privateReply) this
        else copy(scheduleEnabled = true, powBeforeSchedule = powEnabled, powEnabled = false)

    fun scheduleOff(): ScheduleOptions =
        if (!scheduleEnabled && scheduleTimestamp == null) this
        else copy(
            scheduleEnabled = false,
            scheduleTimestamp = null,
            powEnabled = powBeforeSchedule ?: powEnabled,
            powBeforeSchedule = null,
        )

    fun toggleSchedule(): ScheduleOptions = if (scheduleEnabled) scheduleOff() else scheduleOn()

    /** A picked time only sticks while the schedule is on. */
    fun withTimestamp(epochSeconds: Long): ScheduleOptions =
        if (scheduleEnabled) copy(scheduleTimestamp = epochSeconds) else this

    fun togglePow(): ScheduleOptions = if (scheduleEnabled) this else copy(powEnabled = !powEnabled)

    /** The saved PoW preference arriving (composer mount). Held back, not applied, while a schedule is on. */
    fun initPow(enabled: Boolean): ScheduleOptions =
        if (scheduleEnabled) copy(powBeforeSchedule = enabled) else copy(powEnabled = enabled)

    fun withPrivateReply(on: Boolean): ScheduleOptions =
        if (on) scheduleOff().copy(privateReply = true) else copy(privateReply = false)
}

/**
 * The publish-path backstop for "a private reply cannot be scheduled":
 * true when a private reply still carries a schedule, however that state
 * was reached. Such a post must be refused — never gift-wrapped and sent
 * immediately, which is what the user did not ask for.
 */
internal fun privateReplyBlockedBySchedule(
    privateReply: Boolean,
    scheduleEnabled: Boolean,
    scheduleTimestamp: Long?,
): Boolean = privateReply && (scheduleEnabled || scheduleTimestamp != null)

/**
 * Does this post wait out the undo countdown? A scheduled post never
 * does: nothing goes out for minutes at least, it can be cancelled from
 * the scheduled list, and a countdown would eat into the lead time that
 * is checked when it is signed.
 */
internal fun useUndoCountdown(timerEnabled: Boolean, timerSeconds: Int, scheduled: Boolean): Boolean =
    timerEnabled && timerSeconds > 0 && !scheduled

/**
 * Plain wording for every way a schedule call can fail. Exhaustive over
 * [ScheduleFailure], so a new case cannot ship without copy. Wording
 * follows the contract's suggestions and the web client's
 * `scheduleErrorCopy`.
 */
@StringRes
internal fun scheduleFailureMessageRes(failure: ScheduleFailure): Int = when (failure) {
    is ScheduleFailure.AuthFailed -> R.string.schedule_error_auth_failed
    ScheduleFailure.BadRequest -> R.string.schedule_error_generic
    ScheduleFailure.BadRelayMode -> R.string.schedule_error_generic
    ScheduleFailure.BadRelays -> R.string.schedule_error_bad_relays
    ScheduleFailure.BadSignature -> R.string.schedule_error_bad_signature
    ScheduleFailure.PubkeyMismatch -> R.string.schedule_error_pubkey_mismatch
    ScheduleFailure.BadKind -> R.string.schedule_error_bad_kind
    ScheduleFailure.KindNotAllowedForRelayMode -> R.string.schedule_error_kind_not_allowed
    ScheduleFailure.BadPublishTime -> R.string.schedule_error_bad_publish_time
    ScheduleFailure.TooLarge -> R.string.schedule_error_too_large
    ScheduleFailure.PollClosesBeforePublish -> R.string.schedule_error_poll_closes
    is ScheduleFailure.AlreadyScheduled -> R.string.schedule_error_already_scheduled
    ScheduleFailure.QuotaExceeded -> R.string.schedule_error_quota_exceeded
    ScheduleFailure.RateLimited -> R.string.schedule_error_rate_limited
    ScheduleFailure.BadStatus -> R.string.schedule_error_list_failed
    ScheduleFailure.NotFound -> R.string.schedule_error_not_found
    is ScheduleFailure.NotCancellable -> when (failure.status) {
        ScheduleStatus.PUBLISHING -> R.string.schedule_error_not_cancellable_publishing
        ScheduleStatus.SENT -> R.string.schedule_error_not_cancellable_sent
        ScheduleStatus.FAILED -> R.string.schedule_error_not_cancellable_failed
        else -> R.string.schedule_error_not_cancellable
    }
    ScheduleFailure.NotConfigured -> R.string.schedule_error_not_configured
    ScheduleFailure.Internal -> R.string.schedule_error_internal
    ScheduleFailure.SignedOut -> R.string.schedule_error_signed_out
    ScheduleFailure.SignFailed -> R.string.schedule_error_sign_failed
    ScheduleFailure.SignerChangedEvent -> R.string.schedule_error_signer_changed
    ScheduleFailure.Network -> R.string.schedule_error_network
    is ScheduleFailure.Unknown -> R.string.schedule_error_internal
}
