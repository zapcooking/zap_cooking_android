package cooking.zap.app.lazarus

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner

/**
 * Lazarus recovery publish: the one write path. Everything in the core is
 * scan/rank/draft; this turns a draft into a signed event on an explicit
 * user click, then reports exactly what happened.
 *
 * Spec safeguards (SPEC.md "Recover"), with the frontend PR's Copilot
 * findings applied:
 *  - re-read the current version from the write relays immediately before
 *    signing. The re-read is TRI-STATE: when the write relays don't answer,
 *    the publish is refused ("couldn't verify") instead of proceeding
 *    against a stale version (upstream findings 1 and 2 — fail closed);
 *  - if the current version changed since the review, returns [LazarusPublishResult.Changed]
 *    so the UI recomputes the delta and asks again;
 *  - the recovered event is dated after the version it replaces
 *    (buildLazarusRecoveryDraft);
 *  - the signing account must be the list's author — checked again after
 *    signing, so an account switch mid-approval aborts the publish;
 *  - success is judged ONLY on write relays that acknowledged OK true
 *    (upstream finding 5); scan-answered non-write relays get the recovery
 *    as best effort and are never counted.
 */
class LazarusPublisher(private val engine: LazarusScanEngine) {

    sealed class Result {
        /** Accepted by exactly these write relays. */
        data class Published(
            val event: NostrEvent,
            val acceptedWriteRelays: List<String>,
            val unconfirmedWriteRelays: List<String>,
            val bestEffortRelays: List<String>
        ) : Result()

        /** The live current version differs from what the user reviewed. */
        data class Changed(val latest: NostrEvent) : Result()

        data class WrongAccount(val signedBy: String) : Result()

        data class Failed(val reason: String) : Result()
    }

    suspend fun publish(
        chosen: NostrEvent,
        reviewedCurrent: NostrEvent?,
        pubkey: String,
        signer: NostrSigner,
        writeRelays: List<String>,
        respondingScanRelays: List<String>
    ): Result {
        // Fail closed: if the write relays didn't answer, we cannot know
        // whether the reviewed version is still current.
        val freshCurrent: NostrEvent? = when (val read = engine.fetchLatestVersion(chosen.kind, pubkey, writeRelays)) {
            is LatestRead.Unavailable -> return Result.Failed(
                "Your write relays didn't answer, so the current version couldn't be verified. " +
                    "Check your connection and try again."
            )
            is LatestRead.Found -> {
                if (reviewedCurrent?.id != read.event.id) {
                    // Also covers "the scan saw nothing but a version exists now".
                    return Result.Changed(read.event)
                }
                read.event
            }
            is LatestRead.Empty -> {
                if (reviewedCurrent != null) {
                    // The version the user reviewed is gone from the write
                    // relays — treat as changed; the UI re-scans.
                    return Result.Changed(reviewedCurrent)
                }
                null
            }
        }

        val draft = buildLazarusRecoveryDraft(chosen, current = freshCurrent)
        return publishDraft(draft, pubkey, signer, writeRelays, respondingScanRelays)
    }

    /** Publish path used by the UI after its own fresh re-read. */
    suspend fun publishDraft(
        draft: LazarusRecoveryDraft,
        pubkey: String,
        signer: NostrSigner,
        writeRelays: List<String>,
        respondingScanRelays: List<String>
    ): Result {
        val event = try {
            signer.signEvent(kind = draft.kind, content = draft.content, tags = draft.tags, createdAt = draft.created_at)
        } catch (e: Exception) {
            return Result.Failed("Signing was declined or failed: ${e.message ?: "unknown error"}")
        }
        // An account switch mid-approval must not publish under the old review.
        if (event.pubkey != pubkey) return Result.WrongAccount(signedBy = event.pubkey)

        val ack = engine.publishEvent(event, writeRelays, respondingScanRelays)
        return if (ack.acceptedWriteRelays.isEmpty()) {
            Result.Failed("No write relay accepted the recovery — it may not have taken effect.")
        } else {
            Result.Published(
                event = event,
                acceptedWriteRelays = ack.acceptedWriteRelays,
                unconfirmedWriteRelays = ack.unconfirmedWriteRelays,
                bestEffortRelays = ack.extraRelays
            )
        }
    }
}
