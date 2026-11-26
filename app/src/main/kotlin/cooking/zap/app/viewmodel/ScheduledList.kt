package cooking.zap.app.viewmodel

import androidx.annotation.StringRes
import cooking.zap.app.R
import cooking.zap.app.api.ScheduleCancelResult
import cooking.zap.app.api.ScheduleFailure
import cooking.zap.app.api.ScheduleListResult
import cooking.zap.app.api.ScheduleStatus
import cooking.zap.app.api.ScheduledItem
import cooking.zap.app.nostr.NostrEvent

/*
 * The scheduled-posts list, as pure functions so the hermetic JVM suite
 * can pin them. DraftsViewModel and DraftsScreen call these; the contract
 * is docs/schedule-contract.md in zapcooking/frontend.
 */

/** What the scheduled tab knows: the two lists it loads, and why the last load failed, if it did. */
internal data class ScheduledLists(
    val pending: List<ScheduledItem> = emptyList(),
    val failed: List<ScheduledItem> = emptyList(),
    /** Set when the pending call failed; the lists are then whatever was known before. */
    val failure: ScheduleFailure? = null,
) {
    /** Rows in display order: pending soonest first, then failed, most recent first. */
    val rows: List<ScheduledItem> get() = orderScheduled(pending, failed)

    fun without(id: String): ScheduledLists =
        copy(pending = pending.filter { it.id != id }, failed = failed.filter { it.id != id })
}

/**
 * Pending soonest first ("next up"), then failed with the most recent
 * first. The server sends newest publish time first, so pending is
 * re-sorted here, as the contract says a client should. Ties keep a
 * stable order by id; a row never appears twice.
 */
internal fun orderScheduled(pending: List<ScheduledItem>, failed: List<ScheduledItem>): List<ScheduledItem> {
    val next = pending.sortedWith(compareBy<ScheduledItem> { it.publishAt }.thenBy { it.id })
    val gone = failed.sortedWith(compareByDescending<ScheduledItem> { it.publishAt }.thenBy { it.id })
    return (next + gone).distinctBy { it.id }
}

/**
 * Load the list with two calls: `?status=pending`, then `?status=failed`.
 *
 * - Pending is handed to [emit] as soon as it returns, before the failed
 *   call is made, so the user is not kept waiting on the second request.
 * - If the failed call errors, pending is shown anyway; failed rows known
 *   from an earlier load are kept rather than blanked.
 * - If the pending call errors, nothing is replaced: the earlier lists
 *   come back with the reason, and the failed call is not attempted.
 *
 * Rows are trusted only for the status that was asked for.
 */
internal suspend fun loadScheduledLists(
    previous: ScheduledLists,
    list: suspend (ScheduleStatus) -> ScheduleListResult,
    emit: (ScheduledLists) -> Unit,
): ScheduledLists {
    val pending = when (val result = list(ScheduleStatus.PENDING)) {
        is ScheduleListResult.Failed -> return previous.copy(failure = result.failure).also(emit)
        is ScheduleListResult.Loaded -> result.items.filter { it.status == ScheduleStatus.PENDING }
    }
    val afterPending = ScheduledLists(pending = pending, failed = previous.failed, failure = null)
    emit(afterPending)

    val failed = when (val result = list(ScheduleStatus.FAILED)) {
        is ScheduleListResult.Failed -> return afterPending
        is ScheduleListResult.Loaded -> result.items.filter { it.status == ScheduleStatus.FAILED }
    }
    return afterPending.copy(failed = failed).also(emit)
}

/** What a scheduled row is, for its label. */
internal enum class ScheduledKind(@StringRes val labelRes: Int) {
    NOTE(R.string.scheduled_kind_note),
    REPLY(R.string.scheduled_kind_reply),
    COMMENT(R.string.scheduled_kind_comment),
    PICTURE(R.string.scheduled_kind_picture),
    VIDEO(R.string.scheduled_kind_video),
    POLL(R.string.scheduled_kind_poll),
    ZAP_POLL(R.string.scheduled_kind_zap_poll),
    ARTICLE(R.string.scheduled_kind_article),
    OTHER(R.string.scheduled_kind_other),
}

/** What a scheduled row shows of its event. */
internal data class ScheduledPreview(
    val kind: ScheduledKind,
    /** The note text, caption, poll question or article title. May be empty (a picture with no caption). */
    val text: String,
    /**
     * First picture of a kind-20 post, or a video's poster frame when it
     * has one. Null for text kinds: their inline images are drawn by the
     * same rich-text renderer the feed uses.
     */
    val imageUrl: String?,
    val pollOptions: List<String>,
)

/** A sensible preview for every schedulable kind (1, 20, 21, 22, 1068, 1111, 6969, 30023). */
internal fun scheduledPreview(event: NostrEvent): ScheduledPreview {
    fun tagValue(name: String): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.takeIf { it.isNotBlank() }

    /** First `imeta` entry's value for [field] (entries are "field value" strings). */
    fun imeta(field: String): String? = event.tags.asSequence()
        .filter { it.isNotEmpty() && it[0] == "imeta" }
        .mapNotNull { tag ->
            tag.drop(1).firstOrNull { it.startsWith("$field ") }?.substringAfter(' ')?.trim()?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()

    val text = event.content.trim()
    return when (event.kind) {
        1 -> ScheduledPreview(
            kind = if (event.tags.any { it.size >= 2 && it[0] == "e" }) ScheduledKind.REPLY else ScheduledKind.NOTE,
            text = text, imageUrl = null, pollOptions = emptyList(),
        )
        1111 -> ScheduledPreview(ScheduledKind.COMMENT, text, null, emptyList())
        20 -> ScheduledPreview(ScheduledKind.PICTURE, text, imeta("url"), emptyList())
        21, 22 -> ScheduledPreview(ScheduledKind.VIDEO, text, imeta("image"), emptyList())
        1068 -> ScheduledPreview(
            ScheduledKind.POLL, text, null,
            event.tags.filter { it.size >= 3 && it[0] == "option" }.map { it[2] },
        )
        6969 -> ScheduledPreview(
            ScheduledKind.ZAP_POLL, text, null,
            event.tags.filter { it.size >= 3 && it[0] == "poll_option" }.map { it[2] },
        )
        30023 -> ScheduledPreview(
            ScheduledKind.ARTICLE,
            text = tagValue("title") ?: tagValue("summary") ?: text.take(ARTICLE_PREVIEW_CHARS),
            imageUrl = tagValue("image"),
            pollOptions = emptyList(),
        )
        else -> ScheduledPreview(ScheduledKind.OTHER, text, null, emptyList())
    }
}

private const val ARTICLE_PREVIEW_CHARS = 280

/** What to do with a row once its cancel request has answered. */
internal data class CancelOutcome(
    /** Take the row out of the list now (the refresh that follows confirms it). */
    val removeRow: Boolean,
    /** What to tell the user, or null when the row simply going away says it. */
    @StringRes val messageRes: Int?,
)

/**
 * A row is only removed on the server's word:
 *
 *  - cancelled (now, or already) → remove it, and say so;
 *  - `not_found` → it is gone; remove it and say so;
 *  - `not_cancellable` → it is no longer pending (publishing / sent /
 *    failed): remove it from the pending rows and say what happened;
 *  - anything else (network, auth, signer…) → the post is STILL scheduled.
 *    Keep the row and say the cancel did not happen.
 */
internal fun cancelOutcome(result: ScheduleCancelResult): CancelOutcome = when (result) {
    is ScheduleCancelResult.Cancelled -> CancelOutcome(removeRow = true, messageRes = R.string.scheduled_cancelled)
    is ScheduleCancelResult.Failed -> when (result.failure) {
        ScheduleFailure.NotFound, is ScheduleFailure.NotCancellable ->
            CancelOutcome(removeRow = true, messageRes = scheduleFailureMessageRes(result.failure))
        ScheduleFailure.Network -> CancelOutcome(removeRow = false, messageRes = R.string.scheduled_cancel_failed_network)
        else -> CancelOutcome(removeRow = false, messageRes = scheduleCancelFailureMessageRes(result.failure))
    }
}

/**
 * Copy for a cancel that did not happen. The shared wording for a few
 * failures talks about scheduling ("Couldn't schedule this post"), which
 * would be wrong here, so those read as a plain "couldn't cancel".
 */
@StringRes
private fun scheduleCancelFailureMessageRes(failure: ScheduleFailure): Int = when (failure) {
    is ScheduleFailure.AuthFailed,
    ScheduleFailure.SignedOut,
    ScheduleFailure.SignFailed,
    ScheduleFailure.NotConfigured -> scheduleFailureMessageRes(failure)
    else -> R.string.scheduled_cancel_failed
}

/**
 * Copy for a list that would not load. As with cancel, the shared wording
 * for some failures is about scheduling a post; anything that is not
 * specifically about the account or the service reads as "couldn't load".
 */
@StringRes
internal fun scheduleListFailureMessageRes(failure: ScheduleFailure): Int = when (failure) {
    is ScheduleFailure.AuthFailed,
    ScheduleFailure.SignedOut,
    ScheduleFailure.SignFailed,
    ScheduleFailure.NotConfigured,
    ScheduleFailure.Network -> scheduleFailureMessageRes(failure)
    else -> R.string.schedule_error_list_failed
}
