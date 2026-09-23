package cooking.zap.app.repo

import kotlinx.coroutines.TimeoutCancellationException

/**
 * Why an NWC liveness probe concluded what it did (port of zapcooking_ios#140,
 * commit f0b5aa7 — `NwcWallet.Liveness`).
 *
 * `NwcRepository.connect()` only opens the relay subscription — a revoked or
 * offline wallet still "connects", and every real RPC then sits out the full
 * request timeout in silence. [NwcRepository.probeLiveness] round-trips a
 * cheap NIP-47 `get_info` with its own few-second budget and classifies the
 * outcome with [classifyNwcLiveness] so callers learn promptly.
 */
enum class NwcLiveness {
    /** The wallet answered — any decoded response, even an error code, proves a live service. */
    Alive,

    /** The wallet answered and explicitly refused authorization: revoked or restricted. */
    Refused,

    /** Nothing came back inside the probe window — timeout, no relay, no session. */
    Unresponsive,
}

/**
 * An RPC-level refusal/error from the wallet service (NIP-47 `error` response
 * or a response that couldn't be decoded). The `"$code: $message"` message
 * format is what callers display, unchanged from the plain-`Exception` era.
 */
class NwcRpcError(val code: String, val messageText: String) :
    Exception("$code: $messageText")

/** Auth-refusal markers — exact codes plus free-text variants wallets actually send. */
private val REFUSAL_MARKERS = listOf("unauthorized", "revoked", "restricted")

/**
 * Maps a failed probe to liveness. A null failure means a response came back
 * ([NwcRpcError] "DECODE_FAILED" included) — the wallet spoke, even if it
 * refused the method or sent something odd; authorization refusals mean the
 * connection was revoked or restricted; everything else — timeout, no relay
 * accepted the request, no session — is silence. Port of
 * `NwcWallet.classifyLiveness`; getting these backwards either scares users
 * off a working wallet or leaves them staring at a revoked one.
 */
fun classifyNwcLiveness(failure: Throwable?): NwcLiveness = when {
    failure == null -> NwcLiveness.Alive
    failure is NwcRpcError -> {
        if (failure.code == "UNAUTHORIZED" || failure.code == "RESTRICTED" ||
            REFUSAL_MARKERS.any { "${failure.code} ${failure.messageText}".lowercase().contains(it) }
        ) NwcLiveness.Refused
        else NwcLiveness.Alive
    }
    else -> NwcLiveness.Unresponsive
}
