package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the on-wire REQ frame for Nourish `#l` queries. Device evidence on
 * PR #161: pantry indexes `#l` (nak returns hits) but Android's pantry path
 * returned 0 for every labeled REQ — serialization must keep `"#l"` verbatim.
 */
class ClientMessageReqFrameTest {

    @Test
    fun labeledNourishReq_containsHashLByteForByte() {
        val filter = NourishDiscovery.buildNourishAnalysisFilter("protein:30plus")
        val frame = ClientMessage.req("test-sub", filter)

        // Exact tag fragment — not a renamed/escaped form.
        assertTrue(
            "frame must contain #l array verbatim, got: $frame",
            frame.contains("\"#l\":[\"protein:30plus\"]"),
        )
        assertTrue(frame.startsWith("[\"REQ\",\"test-sub\","))
        assertTrue(frame.contains("\"kinds\":[30078]"))
        assertTrue(frame.contains("\"authors\":[\"${NourishParser.SERVICE_PUBKEY}\"]"))
    }

    @Test
    fun unlabeledNourishReq_omitsHashL() {
        val frame = ClientMessage.req(
            "test-sub",
            NourishDiscovery.buildNourishAnalysisFilter(null),
        )
        assertTrue("unlabeled frame must not contain #l, got: $frame", !frame.contains("\"#l\""))
    }

    @Test
    fun filterToJsonObject_lTagsKeyIsHashL() {
        val json = NourishDiscovery.buildNourishAnalysisFilter("protein:30plus").toJsonObject()
        assertEquals(
            """["protein:30plus"]""",
            json["#l"].toString(),
        )
        // Ensure we didn't accidentally write a non-NIP key.
        assertTrue(json.containsKey("#l"))
        assertTrue(!json.containsKey("lTags"))
        assertTrue(!json.containsKey("l"))
    }

    /**
     * The mixed-thread REQ: `#e` (NIP-10 replies) and `#E` (NIP-22 comment
     * roots) must ride as SIBLING filter objects in one REQ — relay-side OR.
     * Folding them into one filter would AND the conditions and match nothing
     * new, and dropping `#E` strands every comment-to-comment branch (relay-
     * verified on a four-deep chain: `#e` returns 1 event, `#E` returns 4).
     */
    @Test
    fun threadReplyReq_emitsHashEAndCapitalHashEAsSiblingFilters() {
        val targets = listOf("rootid", "focalid")
        val replyFilter = Filter(kinds = listOf(1, 5, 1111), eTags = targets, limit = 500)
        val commentRootFilter = Filter(kinds = listOf(1111), capitalETags = targets, limit = 500)

        val frame = ClientMessage.req("thread-replies", listOf(replyFilter, commentRootFilter))
        val arr = Json.parseToJsonElement(frame).jsonArray

        assertEquals("REQ", arr[0].jsonPrimitive.content)
        assertEquals("subId plus two sibling filter objects", 4, arr.size)
        val replyObj = arr[2].jsonObject
        val commentObj = arr[3].jsonObject

        // Each tag name appears in exactly one filter object — never merged.
        assertTrue(replyObj.containsKey("#e"))
        assertTrue(!replyObj.containsKey("#E"))
        assertTrue(commentObj.containsKey("#E"))
        assertTrue(!commentObj.containsKey("#e"))

        assertEquals(targets, replyObj["#e"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(targets, commentObj["#E"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(1, 5, 1111), replyObj["kinds"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(1111), commentObj["kinds"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(500L, replyObj["limit"]!!.jsonPrimitive.long)
        assertEquals(500L, commentObj["limit"]!!.jsonPrimitive.long)
    }

    /** A `#E`-only filter serializes under the uppercase key, not a folded one. */
    @Test
    fun filterToJsonObject_capitalETagsKeyIsCapitalHashE() {
        val json = Filter(kinds = listOf(1111), capitalETags = listOf("rootid")).toJsonObject()
        assertEquals("""["rootid"]""", json["#E"].toString())
        assertTrue(!json.containsKey("#e"))
        assertTrue(!json.containsKey("capitalETags"))
    }
}
