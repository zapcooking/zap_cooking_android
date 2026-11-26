package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.EventRepository

/**
 * Send a signed public note and, only if a relay took it, record it: the last step of the
 * composer's immediate publish. Returns the number of relays sent to; 0 means nothing was
 * sent and nothing was recorded.
 *
 * [send] is tried once; if no relay was reachable, [reconnect] is asked to bring the write
 * relays back and [send] is tried once more. On success the note goes into the feed (so it
 * appears without waiting for the relay echo) and is announced on
 * [EventRepository.publishedEvents].
 *
 * Scheduled posts, private replies and PoW-mined notes never come through here: the composer
 * returns on each of those before this call.
 */
internal suspend fun sendSignedNote(
    event: NostrEvent,
    eventRepo: EventRepository?,
    send: () -> Int,
    reconnect: suspend () -> Int,
    trackPublish: (eventId: String, sentCount: Int) -> Unit,
): Int {
    var sentCount = send()
    // If no relays were reachable, try reconnecting write relays and retry once
    if (sentCount == 0 && reconnect() > 0) sentCount = send()
    if (sentCount == 0) return 0
    trackPublish(event.id, sentCount)
    // Insert into feed so the note appears immediately without waiting for relay echo
    eventRepo?.addEvent(event)
    eventRepo?.announcePublished(event)
    return sentCount
}
