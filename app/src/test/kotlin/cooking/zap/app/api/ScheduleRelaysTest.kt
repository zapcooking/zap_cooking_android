package cooking.zap.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `relays` picker. The `pick` and `normalize` cases are the web
 * client's own, ported from zapcooking/frontend
 * `src/lib/scheduleClient.test.ts` (`pickScheduleRelays`) and
 * `src/lib/scheduleRelays.test.ts` (`normalizeScheduleRelay`), so both
 * clients turn the same relay list into the same `relays` value. Web's
 * non-string entries (`42`) have no Kotlin equivalent in a `List<String?>`
 * and are represented by `null` only.
 */
class ScheduleRelaysTest {

    // --- ported: pickScheduleRelays ---

    @Test
    fun keepsTheFirstFourUsableRelays_inOrder_normalized() {
        assertEquals(
            listOf("wss://a.example.com", "wss://b.example.com", "wss://c.example.com/inbox", "wss://d.example.com"),
            ScheduleRelays.pick(
                listOf(
                    "wss://A.example.com/",
                    "wss://b.example.com:443",
                    "wss://c.example.com/inbox/",
                    "wss://d.example.com",
                    "wss://e.example.com",
                )
            ),
        )
    }

    @Test
    fun dropsWhatTheServerWouldReject_soOneBadRelayCannotSinkTheRequest() {
        assertEquals(
            listOf("wss://ok.example.com"),
            ScheduleRelays.pick(
                listOf(
                    "ws://insecure.example.com",
                    "wss://localhost",
                    "wss://192.168.1.4",
                    "wss://[::1]",
                    "wss://nas.lan",
                    "wss://abc.onion",
                    "wss://user:pw@a.example.com",
                    "wss://a.example.com/${"p".repeat(180)}",
                    null,
                    "",
                    "wss://ok.example.com",
                )
            ),
        )
    }

    @Test
    fun doesNotSpendPlacesOnTheSchedulersOwnRelays_purplepages_orDuplicates() {
        assertEquals(
            listOf("wss://a.example.com", "wss://b.example.com"),
            ScheduleRelays.pick(
                listOf(
                    "wss://nos.lol",
                    "wss://relay.primal.net/",
                    "wss://purplepag.es",
                    "wss://Nos.lol/",
                    "wss://a.example.com",
                    "wss://A.example.com/",
                    "wss://b.example.com",
                )
            ),
        )
    }

    @Test
    fun anEmptyOrUnusableListIsEmpty() {
        assertEquals(emptyList<String>(), ScheduleRelays.pick(emptyList()))
        assertEquals(emptyList<String>(), ScheduleRelays.pick(listOf("wss://nos.lol", "nonsense")))
    }

    // --- ported: normalizeScheduleRelay ---

    @Test
    fun normalize_matchesTheServersCanonicalSpelling() {
        val cases = listOf(
            "wss://Relay.Example.COM" to "wss://relay.example.com",
            "WSS://relay.example.com/" to "wss://relay.example.com",
            "wss://relay.example.com:443" to "wss://relay.example.com",
            "wss://relay.example.com:4443/" to "wss://relay.example.com:4443",
            "wss://relay.example.com/inbox/" to "wss://relay.example.com/inbox",
            "wss://relay.example.com/Inbox?key=A#frag" to "wss://relay.example.com/Inbox?key=A",
            "wss://relay.example.com." to "wss://relay.example.com.",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, ScheduleRelays.normalize(input))
        }
    }

    // --- the server's limits (contract "Relays"), as the picker must respect them ---

    @Test
    fun eachUrlMustBeUnder200Characters() {
        fun at(n: Int) = "wss://a.example.com/" + "p".repeat(n - "wss://a.example.com/".length)
        assertEquals(199, at(199).length)
        assertEquals(listOf(at(199)), ScheduleRelays.pick(listOf(at(199))))
        assertEquals(emptyList<String>(), ScheduleRelays.pick(listOf(at(200))))
    }

    @Test
    fun theCapAppliesAfterDuplicatesAreFolded() {
        val four = listOf("wss://a.example.com", "wss://b.example.com", "wss://c.example.com", "wss://d.example.com")
        assertEquals(four, ScheduleRelays.pick(listOf("wss://a.example.com", "wss://A.example.com/") + four.drop(1)))
        assertEquals(4, ScheduleRelays.MAX_RELAYS)
    }

    @Test
    fun pathsDistinguishRelays_aFragmentDoesNot() {
        assertEquals(
            listOf("wss://r.example.com/a", "wss://r.example.com/b"),
            ScheduleRelays.pick(listOf("wss://r.example.com/a", "wss://r.example.com/b", "wss://r.example.com/a#x")),
        )
    }

    @Test
    fun isPublicRelayUrl_refusesEverythingTheContractLists() {
        val refused = listOf(
            "ws://a.example.com",
            "https://a.example.com",
            "wss://127.0.0.1",
            "wss://2130706433",
            "wss://0x7f.1",
            "wss://[::1]",
            "wss://[2001:db8::1]",
            "wss://localhost",
            "wss://relay",
            "wss://nas.lan",
            "wss://printer.local",
            "wss://box.internal",
            "wss://abc.onion",
            "wss://abc.i2p",
            "wss://user@a.example.com",
            "wss://user:pw@a.example.com",
            "wss://a.example.com/with space",
            " wss://a.example.com",
            "wss://bad_host.example.com",
            "wss://-bad.example.com",
            "",
            "nonsense",
        )
        for (url in refused) assertFalse(url, ScheduleRelays.isPublicRelayUrl(url))
        assertFalse(ScheduleRelays.isPublicRelayUrl(null))
    }

    @Test
    fun isPublicRelayUrl_acceptsOrdinaryPublicRelays() {
        for (url in listOf(
            "wss://relay.damus.io",
            "wss://nostr.wine/",
            "WSS://Relay.Example.com",
            "wss://relay.example.com:4443/inbox?x=1",
            "wss://sub.domain.relay.co.uk",
        )) {
            assertTrue(url, ScheduleRelays.isPublicRelayUrl(url))
        }
    }
}
