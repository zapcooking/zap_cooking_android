package cooking.zap.app.lazarus

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner

/**
 * The app's own copy of a list, for the re-read before a restore. The app's
 * stores keep a parsed list and the created_at of the version it came from;
 * [event] is the version itself when the app still holds it.
 */
data class LazarusLocalCopy(val createdAt: Long, val event: NostrEvent? = null)

/** Where a restore is: re-reading current, waiting for the signer, or publishing. */
enum class LazarusRestoreStage { CONFIRMING, SIGNING, PUBLISHING }

/**
 * Lazarus recovery publish: the one write path. Everything in the core is
 * scan/rank/draft; this turns a draft into a signed event on an explicit
 * user click, then reports exactly what happened.
 *
 * Spec safeguards (SPEC.md "Recover"):
 *  - the signing account must be the list's author, checked before the
 *    signer is asked and again after it answers, so an account switch
 *    during the approval aborts the publish;
 *  - immediately before signing, the current version is re-read from the
 *    app's own copy and every write relay ([checkLazarusCurrent]). Only a
 *    strictly newer version counts as a change, and it comes back as
 *    [Result.Changed] for the UI to recompute the delta and ask again; an
 *    older copy is no edit. When no write relay answers, nothing is signed
 *    ([Result.Unconfirmed]) unless the user gave the separate override
 *    confirmation after a failed retry;
 *  - the recovery is dated after the reviewed version, the newest known at
 *    signing, never after an older copy the re-read found;
 *  - success is judged on the write relays: at least one must answer OK
 *    true, and the report names which did and which didn't.
 */
class LazarusPublisher(
    private val engine: LazarusScanEngine,
    /** Signature check; injected so the JVM tests run without the secp256k1 native library. */
    private val verify: (NostrEvent) -> Boolean = { it.verifySignature() },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    sealed class Result {
        /** At least one write relay accepted the recovery. */
        data class Published(val report: LazarusPublishReport) : Result()

        /** Signed and sent, but no write relay accepted it: the recovery didn't take effect. */
        data class NotAccepted(val report: LazarusPublishReport) : Result()

        /**
         * A version newer than the reviewed one appeared (another device or
         * client edited the list): it becomes current, found on [foundOn]
         * (empty when only the app's copy had it). Nothing was signed.
         */
        data class Changed(val latest: NostrEvent, val foundOn: List<String>, val confirmed: Boolean) : Result()

        /** The app's own copy is newer than anything the re-read could read. Nothing was signed. */
        data class ChangedLocally(val createdAt: Long) : Result()

        /** No write relay answered the re-read, so current couldn't be confirmed. Nothing was signed. */
        object Unconfirmed : Result()

        /** The signing account isn't the list's author (or changed during the approval). Nothing was published. */
        data class WrongAccount(val signedBy: String?) : Result()

        /** The signer declined or failed. Nothing was published. */
        data class SignFailed(val message: String?) : Result()

        /** The signer returned a different event than the one reviewed. Nothing was published. */
        object SignerMismatch : Result()
    }

    /**
     * Restore [chosen] over [reviewedCurrent], the version the reviewed delta
     * was computed against. [activePubkey] reads the app's active account at
     * the moment of each check. [allowUnconfirmed] is the explicit override
     * the UI offers only after a failed retry; it lets an unconfirmed re-read
     * through, never a changed one.
     */
    suspend fun restore(
        chosen: NostrEvent,
        reviewedCurrent: NostrEvent?,
        pubkey: String,
        signer: NostrSigner,
        activePubkey: () -> String?,
        localCopy: LazarusLocalCopy?,
        respondingRelays: List<String>,
        allowUnconfirmed: Boolean = false,
        onStage: (LazarusRestoreStage) -> Unit = {}
    ): Result {
        // Only the account the list belongs to can restore it
        if (chosen.pubkey != pubkey || signer.pubkeyHex != pubkey || activePubkey() != pubkey) {
            return Result.WrongAccount(signedBy = signer.pubkeyHex)
        }

        onStage(LazarusRestoreStage.CONFIRMING)
        val read = engine.readCurrent(chosen.kind, pubkey)
        val local = localCopy?.event?.takeIf { isLazarusVersion(it, chosen.kind, pubkey, verify) }
        when (val check = checkLazarusCurrent(reviewedCurrent, local, read.answers, localCopy?.createdAt)) {
            is LazarusCurrentCheck.Changed -> return Result.Changed(
                latest = check.current,
                foundOn = read.answers.filter { answer -> answer.events.any { it.id == check.current.id } }
                    .map { it.relayUrl },
                confirmed = read.answers.any { it.answered }
            )
            is LazarusCurrentCheck.ChangedLocally -> return Result.ChangedLocally(check.createdAt)
            LazarusCurrentCheck.Unconfirmed -> if (!allowUnconfirmed) return Result.Unconfirmed
            is LazarusCurrentCheck.Proceed -> Unit
        }

        // Dated after the version the delta was computed against — never an
        // older copy the re-read found, or the recovery loses to the clobber
        val draft = buildLazarusRecoveryDraft(chosen, current = reviewedCurrent, now = now())
        onStage(LazarusRestoreStage.SIGNING)
        val signed = try {
            signer.signEvent(kind = draft.kind, content = draft.content, tags = draft.tags, createdAt = draft.created_at)
        } catch (e: Exception) {
            return Result.SignFailed(e.message)
        }
        // An account switch mid-approval must not publish under the old review
        if (signed.pubkey != pubkey || activePubkey() != pubkey || !runCatching { verify(signed) }.getOrDefault(false)) {
            return Result.WrongAccount(signedBy = signed.pubkey)
        }
        // Publish exactly what was reviewed
        if (signed.kind != draft.kind || signed.content != draft.content ||
            signed.tags != draft.tags || signed.created_at != draft.created_at
        ) {
            return Result.SignerMismatch
        }

        onStage(LazarusRestoreStage.PUBLISHING)
        val report = engine.publish(signed, pubkey, respondingRelays)
        return if (report.succeeded) Result.Published(report) else Result.NotAccepted(report)
    }
}
