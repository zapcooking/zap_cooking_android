package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mute lists carry four item types across public tags and encrypted private
 * content — accounts, words, threads, hashtags. A copy that keeps only some
 * of them republishes a reduced list on the next edit, silently undoing the
 * mutes the user made elsewhere (or restored with Lazarus).
 */
class Nip51MuteListTest {

    private fun event(tags: List<List<String>>, content: String = "") = NostrEvent(
        id = "event".padEnd(64, '0'),
        pubkey = "pubkey".padEnd(64, '0'),
        created_at = 1000,
        kind = Nip51.KIND_MUTE_LIST,
        tags = tags,
        content = content,
        sig = "sig"
    )

    @Test
    fun `parses threads and hashtags from public tags`() {
        val parsed = Nip51.parseMuteList(
            event(
                listOf(
                    listOf("p", "alice"),
                    listOf("word", "spam"),
                    listOf("e", "thread-id"),
                    listOf("t", "cooking")
                )
            )
        )
        assertEquals(setOf("alice"), parsed.pubkeys)
        assertEquals(setOf("spam"), parsed.words)
        assertEquals(setOf("thread-id"), parsed.threads)
        assertEquals(setOf("cooking"), parsed.hashtags)
    }

    @Test
    fun `parses threads and hashtags from decrypted private items`() {
        val content = Nip51.buildMuteListContent(
            blockedPubkeys = setOf("alice"),
            mutedWords = setOf("spam"),
            mutedThreads = setOf("thread-id"),
            mutedHashtags = setOf("cooking")
        )
        val parsed = Nip51.parsePrivateTags(content)
        assertEquals(setOf("alice"), parsed.pubkeys)
        assertEquals(setOf("spam"), parsed.words)
        assertEquals(setOf("thread-id"), parsed.threads)
        assertEquals(setOf("cooking"), parsed.hashtags)
    }

    @Test
    fun `the built content carries all four item types verbatim`() {
        val content = Nip51.buildMuteListContent(
            blockedPubkeys = setOf("alice"),
            mutedWords = setOf("spam"),
            mutedThreads = setOf("thread-id"),
            mutedHashtags = setOf("cooking")
        )
        val tags = Nip51.parsePrivateTagsGeneric(content).map { it.take(2) }
        assertEquals(
            setOf(
                listOf("p", "alice"),
                listOf("word", "spam"),
                listOf("e", "thread-id"),
                listOf("t", "cooking")
            ),
            tags.toSet()
        )
    }
}
