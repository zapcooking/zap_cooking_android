package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of web's nameSearch rules — the query-hygiene and observer-scoping
 *  cases that caught real mistakes in Sidecar's version of the change. */
class BrainstormSearchTest {

    @Test
    fun `clean query keeps plain words`() {
        assertEquals("alice", BrainstormSearch.cleanQuery("alice"))
        assertEquals("alice", BrainstormSearch.cleanQuery("@alice"))
        assertEquals("so up stick", BrainstormSearch.cleanQuery("so\nup\tstick"))
    }

    @Test
    fun `clean query strips relay instruction words`() {
        assertEquals("alice", BrainstormSearch.cleanQuery("observer:${BrainstormSearch.HOUSE_OBSERVER} alice"))
        assertEquals("alice", BrainstormSearch.cleanQuery("include:spam alice"))
        assertEquals("alice", BrainstormSearch.cleanQuery("sort:rank alice"))
    }

    @Test
    fun `clean query drops exclusion prefixes and quote-only words`() {
        assertNull(BrainstormSearch.cleanQuery("-alice"))
        assertNull(BrainstormSearch.cleanQuery("'\"*"))
        assertNull(BrainstormSearch.cleanQuery("   "))
    }

    @Test
    fun `clean query caps at 64 chars`() {
        assertEquals(64, BrainstormSearch.cleanQuery("a".repeat(100))?.length)
    }

    @Test
    fun `observer token goes to brainstorm only`() {
        val brainstorm = BrainstormSearch.searchQueryString(BrainstormSearch.RELAY, "soup")
        assertEquals("soup observer:${BrainstormSearch.HOUSE_OBSERVER}", brainstorm)

        val other = BrainstormSearch.searchQueryString("wss://search.nos.today", "soup")
        assertEquals("soup", other)
        assertFalse(other!!.contains(BrainstormSearch.HOUSE_OBSERVER))
    }

    @Test
    fun `unsendable query asks nobody`() {
        assertNull(BrainstormSearch.searchQueryString(BrainstormSearch.RELAY, "-alice"))
        assertNull(BrainstormSearch.searchQueryString("wss://search.nos.today", "'\"*"))
    }

    @Test
    fun `default relay pair is brainstorm primary with nos today behind it`() {
        assertEquals(listOf(BrainstormSearch.RELAY, "wss://search.nos.today"), BrainstormSearch.DEFAULT_RELAYS)
    }
}
