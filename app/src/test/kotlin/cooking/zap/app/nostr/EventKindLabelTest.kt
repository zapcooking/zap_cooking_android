package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ports wisp-ios `EventKindLabelTests`: the details-drawer row reads
 * "KIND <n> · <LABEL>", known kinds get the human word, unknown kinds
 * degrade to the bare number.
 */
class EventKindLabelTest {

    @Test
    fun knownKindsGetTheirLabel() {
        assertEquals("KIND 0 · PROFILE", EventKindLabel.label(0))
        assertEquals("KIND 1 · NOTE", EventKindLabel.label(1))
        assertEquals("KIND 3 · FOLLOWS", EventKindLabel.label(3))
        assertEquals("KIND 4 · DM", EventKindLabel.label(4))
        assertEquals("KIND 5 · DELETION", EventKindLabel.label(5))
        assertEquals("KIND 6 · REPOST", EventKindLabel.label(6))
        assertEquals("KIND 7 · REACTION", EventKindLabel.label(7))
        assertEquals("KIND 20 · PICTURE", EventKindLabel.label(20))
        assertEquals("KIND 21 · VIDEO", EventKindLabel.label(21))
        assertEquals("KIND 22 · VIDEO", EventKindLabel.label(22))
        assertEquals("KIND 1059 · GIFT WRAP", EventKindLabel.label(1059))
        assertEquals("KIND 1068 · POLL", EventKindLabel.label(1068))
        // The acceptance case: Derek's Ditto replies read beside "Posted via Ditto".
        assertEquals("KIND 1111 · COMMENT", EventKindLabel.label(1111))
        assertEquals("KIND 6969 · ZAP POLL", EventKindLabel.label(6969))
        assertEquals("KIND 30023 · ARTICLE", EventKindLabel.label(30023))
        assertEquals("KIND 30078 · APP DATA", EventKindLabel.label(30078))
    }

    @Test
    fun unknownKindDegradesToBareNumber() {
        assertEquals("KIND 2", EventKindLabel.label(2))
        assertEquals("KIND 22242", EventKindLabel.label(22242))
        assertEquals("KIND -1", EventKindLabel.label(-1))
    }
}
