package cooking.zap.app.api

import cooking.zap.app.nostr.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/*
 * Types and pure mapping for the scheduled-posts API
 * (`POST|GET /api/schedule`, `DELETE /api/schedule/{id}`).
 *
 * The contract is `docs/schedule-contract.md` in zapcooking/frontend; the
 * web client it was written alongside is `src/lib/scheduleClient.ts`. The
 * calls themselves live in [ZapCookingApi] (signForSchedule, scheduleEvent,
 * listScheduled, cancelScheduled).
 */

/** A scheduled row's lifecycle state. Only [PENDING] can be cancelled. */
enum class ScheduleStatus(val wire: String) {
    PENDING("pending"),
    PUBLISHING("publishing"),
    SENT("sent"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String?): ScheduleStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** One row of `GET /api/schedule`. */
data class ScheduledItem(
    /** The event id. */
    val id: String,
    /** Unix seconds; equals `event.created_at`. */
    val publishAt: Long,
    val relays: List<String>,
    val status: ScheduleStatus,
    val attempts: Int,
    /** Diagnostic text from the sweep or a relay. Detail only — show under plain copy, never parse. */
    val lastError: String?,
    val sentAt: Long?,
    /** The full signed event, exactly as it was scheduled. */
    val event: NostrEvent,
)

/**
 * Every way a schedule call can fail: one case per contract error code,
 * then the client-side ones. Exhaustive `when` over this is how the UI
 * maps each to copy, so a new case cannot ship without wording.
 */
sealed interface ScheduleFailure {
    // --- server codes, verbatim from the contract ---

    /** 401 `auth_failed`, still refused after one retry with a fresh header. [detail] is `header` / `event` / `binding`. */
    data class AuthFailed(val detail: String? = null) : ScheduleFailure
    data object BadRequest : ScheduleFailure
    data object BadRelayMode : ScheduleFailure
    /** Refused relay list, and there was nothing left to drop (the one retry without `relays` is already spent or not applicable). */
    data object BadRelays : ScheduleFailure
    data object BadSignature : ScheduleFailure
    data object PubkeyMismatch : ScheduleFailure
    data object BadKind : ScheduleFailure
    data object KindNotAllowedForRelayMode : ScheduleFailure
    /** Also returned by [ZapCookingApi.signForSchedule] before signing when the time is out of range. */
    data object BadPublishTime : ScheduleFailure
    data object TooLarge : ScheduleFailure
    data object PollClosesBeforePublish : ScheduleFailure
    /**
     * This exact event is already stored and will NOT go out: its row is
     * `cancelled` or `failed` ([status]; null when the server sent one we
     * don't know). A row that is pending/publishing/sent is reported as
     * success instead. Retrying means signing a new event.
     */
    data class AlreadyScheduled(val status: ScheduleStatus?) : ScheduleFailure
    data object QuotaExceeded : ScheduleFailure
    /** Do not retry automatically. */
    data object RateLimited : ScheduleFailure
    data object BadStatus : ScheduleFailure
    data object NotFound : ScheduleFailure
    /** The row is no longer pending. [status] says what happened (publishing / sent / failed). */
    data class NotCancellable(val status: ScheduleStatus?) : ScheduleFailure
    data object NotConfigured : ScheduleFailure
    /** A POST that got this is safe to retry with the same signed event. */
    data object Internal : ScheduleFailure

    // --- client side ---

    /** No signer: signed out, or a read-only account. */
    data object SignedOut : ScheduleFailure
    /** The signer refused, was dismissed, or threw. */
    data object SignFailed : ScheduleFailure
    /** The signer returned something other than the event it was asked to sign (re-dated, altered, or with a bad signature). Never sent. */
    data object SignerChangedEvent : ScheduleFailure
    /** The request never got an answer. A POST is safe to retry with the same signed event. */
    data object Network : ScheduleFailure
    /** An error code this client does not know (codes may be added), or an unreadable response. Generic failure. */
    data class Unknown(val code: String? = null) : ScheduleFailure
}

sealed interface ScheduleSignResult {
    /** [event] is verified to be exactly what was asked for; hand it to [ZapCookingApi.scheduleEvent] unchanged. */
    data class Signed(val event: NostrEvent) : ScheduleSignResult
    data class Failed(val failure: ScheduleFailure) : ScheduleSignResult
}

sealed interface ScheduleEventResult {
    data class Scheduled(
        val id: String,
        val publishAt: Long,
        /** As stored by the server (normalized). */
        val relays: List<String>,
        val status: ScheduleStatus,
        /** True when an earlier attempt had already stored this exact event (409 treated as success). */
        val alreadyScheduled: Boolean,
    ) : ScheduleEventResult
    data class Failed(val failure: ScheduleFailure) : ScheduleEventResult
}

sealed interface ScheduleListResult {
    /** Server order: newest `publishAt` first, at most 100. */
    data class Loaded(val items: List<ScheduledItem>) : ScheduleListResult
    data class Failed(val failure: ScheduleFailure) : ScheduleListResult
}

sealed interface ScheduleCancelResult {
    data class Cancelled(
        val id: String,
        /** True when the row was already cancelled before this call. */
        val alreadyCancelled: Boolean,
    ) : ScheduleCancelResult
    data class Failed(val failure: ScheduleFailure) : ScheduleCancelResult
}

/** Pure request building and response mapping, unit-tested against the contract's shapes. */
internal object ScheduleWire {
    /** Contract floor is 120 s; the client refuses to sign anything 3 minutes or less away. */
    const val MIN_LEAD_SECONDS = 180L

    /** Contract: strictly less than 90 days out. */
    const val MAX_LEAD_SECONDS = 90L * 86_400L

    private val json = Json { ignoreUnknownKeys = true }
    private val EVENT_ID = Regex("^[0-9a-f]{64}$")

    /** Row statuses that mean an earlier create attempt took effect. */
    private val LANDED = setOf(ScheduleStatus.PENDING, ScheduleStatus.PUBLISHING, ScheduleStatus.SENT)

    fun isEventId(id: String): Boolean = EVENT_ID.matches(id)

    /**
     * The POST body. `created_at` is emitted as a JSON number; `relays` is
     * left out entirely when empty. The returned string is both hashed
     * into the NIP-98 `payload` tag and sent, byte for byte.
     */
    fun buildScheduleBody(event: NostrEvent, relays: List<String>): String = buildJsonObject {
        put("event", buildJsonObject {
            put("id", event.id)
            put("pubkey", event.pubkey)
            put("created_at", event.created_at)
            put("kind", event.kind)
            put("tags", buildJsonArray {
                event.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) }
            })
            put("content", event.content)
            put("sig", event.sig)
        })
        put("relay_mode", "all")
        if (relays.isNotEmpty()) {
            put("relays", buildJsonArray { relays.forEach { add(it) } })
        }
    }.toString()

    /** The response body as a JSON object; anything else (empty, HTML, an array) is `{}`. */
    fun parseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        } ?: emptyList()

    fun errorCode(data: JsonObject): String? = data.string("error")

    /**
     * The server's `error` as a [ScheduleFailure]. Branches on the code,
     * never on the HTTP status; an unrecognized or missing code is
     * [ScheduleFailure.Unknown].
     */
    fun failureOf(data: JsonObject): ScheduleFailure {
        val code = errorCode(data)
        return when (code) {
            "auth_failed" -> ScheduleFailure.AuthFailed(data.string("detail"))
            "bad_request" -> ScheduleFailure.BadRequest
            "bad_relay_mode" -> ScheduleFailure.BadRelayMode
            "bad_relays" -> ScheduleFailure.BadRelays
            "bad_signature" -> ScheduleFailure.BadSignature
            "pubkey_mismatch" -> ScheduleFailure.PubkeyMismatch
            "bad_kind" -> ScheduleFailure.BadKind
            "kind_not_allowed_for_relay_mode" -> ScheduleFailure.KindNotAllowedForRelayMode
            "bad_publish_time" -> ScheduleFailure.BadPublishTime
            "too_large" -> ScheduleFailure.TooLarge
            "poll_closes_before_publish" -> ScheduleFailure.PollClosesBeforePublish
            "already_scheduled" -> ScheduleFailure.AlreadyScheduled(ScheduleStatus.fromWire(data.string("status")))
            "quota_exceeded" -> ScheduleFailure.QuotaExceeded
            "rate_limited" -> ScheduleFailure.RateLimited
            "bad_status" -> ScheduleFailure.BadStatus
            "not_found" -> ScheduleFailure.NotFound
            "not_cancellable" -> ScheduleFailure.NotCancellable(ScheduleStatus.fromWire(data.string("status")))
            "not_configured" -> ScheduleFailure.NotConfigured
            "internal" -> ScheduleFailure.Internal
            else -> ScheduleFailure.Unknown(code)
        }
    }

    /**
     * Map a `POST /api/schedule` response. [sentRelays] is what this
     * request carried, used only to describe a 409-as-success (the 409
     * body does not repeat the stored list).
     */
    fun mapScheduleResponse(
        code: Int,
        data: JsonObject,
        event: NostrEvent,
        sentRelays: List<String>,
    ): ScheduleEventResult {
        if (code == 201) {
            return ScheduleEventResult.Scheduled(
                id = data.string("id") ?: event.id,
                publishAt = data.long("publish_at") ?: event.created_at,
                relays = data.strings("relays"),
                status = ScheduleStatus.fromWire(data.string("status")) ?: ScheduleStatus.PENDING,
                alreadyScheduled = false,
            )
        }
        val failure = failureOf(data)
        if (failure is ScheduleFailure.AlreadyScheduled) {
            // An earlier attempt landed: that is the outcome the caller wanted.
            // Cancelled, failed, or a status we don't know falls through:
            // this exact event will not go out.
            val rowStatus = failure.status
            if (rowStatus != null && rowStatus in LANDED) {
                return ScheduleEventResult.Scheduled(
                    id = event.id,
                    publishAt = event.created_at,
                    relays = sentRelays,
                    status = rowStatus,
                    alreadyScheduled = true,
                )
            }
        }
        return ScheduleEventResult.Failed(failure)
    }

    /** One list row, or null when this client cannot read it (skipped rather than failing the list). */
    fun parseItem(raw: JsonElement): ScheduledItem? {
        val row = raw as? JsonObject ?: return null
        val id = row.string("id") ?: return null
        val publishAt = row.long("publish_at") ?: return null
        val status = ScheduleStatus.fromWire(row.string("status")) ?: return null
        val event = try {
            json.decodeFromJsonElement(NostrEvent.serializer(), row["event"] ?: return null)
        } catch (_: Exception) {
            return null
        }
        return ScheduledItem(
            id = id,
            publishAt = publishAt,
            relays = row.strings("relays"),
            status = status,
            attempts = row.long("attempts")?.toInt() ?: 0,
            lastError = row.string("last_error"),
            sentAt = row.long("sent_at"),
            event = event,
        )
    }

    fun mapListResponse(code: Int, data: JsonObject): ScheduleListResult {
        val items = data["items"] as? JsonArray
        if (code == 200 && items != null) {
            return ScheduleListResult.Loaded(items.mapNotNull(::parseItem))
        }
        return ScheduleListResult.Failed(failureOf(data))
    }

    fun mapCancelResponse(code: Int, data: JsonObject, id: String): ScheduleCancelResult {
        if (code == 200 && data.string("status") == ScheduleStatus.CANCELLED.wire) {
            return ScheduleCancelResult.Cancelled(id, alreadyCancelled = false)
        }
        val failure = failureOf(data)
        // Already cancelled is the outcome the caller wanted.
        if (failure is ScheduleFailure.NotCancellable && failure.status == ScheduleStatus.CANCELLED) {
            return ScheduleCancelResult.Cancelled(id, alreadyCancelled = true)
        }
        return ScheduleCancelResult.Failed(failure)
    }
}
