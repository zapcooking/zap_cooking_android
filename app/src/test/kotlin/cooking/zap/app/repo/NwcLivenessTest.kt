package cooking.zap.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liveness classifier decides what a failed NWC probe means (port of the
 * iOS `NwcLivenessTests`, zapcooking_ios#140): a decoded response of any kind
 * — even an error code or a malformed one — proves a live wallet service
 * answered, an authorization refusal means the connection was revoked or
 * restricted, and silence — timeout, no relay, no session — means
 * unresponsive. `WalletViewModel` turns these into the few-second alert
 * copy; getting them backwards either scares users off a working wallet or
 * leaves them staring at a revoked one.
 */
class NwcLivenessTest {

    // --- A response means alive ---

    @Test
    fun `a response always means alive`() {
        assertEquals(NwcLiveness.Alive, classifyNwcLiveness(null))
    }

    // --- Authorization refusals mean refused ---

    @Test
    fun `authorization refusals mean refused`() {
        assertEquals(
            NwcLiveness.Refused,
            classifyNwcLiveness(NwcRpcError("UNAUTHORIZED", ""))
        )
        assertEquals(
            NwcLiveness.Refused,
            classifyNwcLiveness(NwcRpcError("RESTRICTED", "permission denied"))
        )
        assertEquals(
            NwcLiveness.Refused,
            classifyNwcLiveness(NwcRpcError("INTERNAL", "connection was revoked by the user"))
        )
        assertEquals(
            NwcLiveness.Refused,
            classifyNwcLiveness(NwcRpcError("OTHER", "Unauthorized client"))
        )
    }

    // --- Odd answers still prove a live wallet ---

    @Test
    fun `answered but refused the method means alive`() {
        // A wallet that says "not supported" is alive — it spoke.
        assertEquals(
            NwcLiveness.Alive,
            classifyNwcLiveness(NwcRpcError("NOT_SUPPORTED", "get_info"))
        )
        // A malformed payload came from somewhere — a live service sent it.
        assertEquals(
            NwcLiveness.Alive,
            classifyNwcLiveness(NwcRpcError("DECODE_FAILED", "weird payload"))
        )
    }

    // --- Silence means unresponsive ---

    @Test
    fun `silence means unresponsive`() {
        // The probe's own timeout is caught by type in probeLiveness (its
        // constructor is internal, so it can't be constructed here); any
        // non-RPC failure — timeout included — classifies as silence.
        assertEquals(
            NwcLiveness.Unresponsive,
            classifyNwcLiveness(RuntimeException("Timed out waiting for response"))
        )
        assertEquals(
            NwcLiveness.Unresponsive,
            classifyNwcLiveness(Exception("Not connected"))
        )
        assertEquals(
            NwcLiveness.Unresponsive,
            classifyNwcLiveness(Exception("No relay accepted the request"))
        )
    }

    // --- The display format callers rely on ---

    @Test
    fun `rpc error message keeps the code colon message display format`() {
        assertEquals("UNAUTHORIZED: nope", NwcRpcError("UNAUTHORIZED", "nope").message!!)
        assertTrue(NwcRpcError("DECODE_FAILED", "bad").message!!.startsWith("DECODE_FAILED:"))
    }
}
