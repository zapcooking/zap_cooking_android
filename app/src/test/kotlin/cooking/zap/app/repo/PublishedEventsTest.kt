package cooking.zap.app.repo

import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.LocalSigner
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.viewmodel.sendSignedNote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * [EventRepository.publishedEvents]: one emission for an event this device published just
 * now, from each of the two sites that publish (the note composer's [sendSignedNote] and
 * [RecipePublisher]'s [signCacheAndBroadcast]), and none for a publish that failed.
 *
 * The scheduled and private-reply paths live in ComposeViewModel, which needs Android, so
 * they are not driven here. What is pinned instead is where the signal can come from at all:
 * which sources call `announcePublished`, and that the composer's one call into
 * [sendSignedNote] sits after the scheduled and PoW returns and outside the private path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublishedEventsTest {

    private val keys = Keys.generate()
    private val signer = LocalSigner(keys.privkey, keys.pubkey)

    private suspend fun note(content: String = "hello"): NostrEvent = signer.signEvent(1, content)

    /** Everything [repo] announces from here to the end of the test. */
    private fun TestScope.published(repo: EventRepository): List<NostrEvent> {
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.publishedEvents.collect { seen += it }
        }
        return seen
    }

    // --- the flow itself ---

    @Test
    fun `adding or caching an event is not publishing it`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)
        repo.addEvent(note("from a relay"))
        repo.cacheEvent(note("a draft or an echo"))
        assertTrue(seen.isEmpty())
    }

    // --- notes ---

    @Test
    fun `a note a relay took is announced once and is in the cache`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)
        val event = note()
        val tracked = mutableListOf<Pair<String, Int>>()

        val sent = sendSignedNote(event, repo, send = { 3 }, reconnect = { fail("no reconnect needed"); 0 }) { id, n ->
            tracked += id to n
        }

        assertEquals(3, sent)
        assertEquals(listOf(event), seen)
        assertEquals(listOf(event.id to 3), tracked)
        assertNotNull(repo.getEvent(event.id))
    }

    @Test
    fun `a note sent on the retry after a reconnect is announced once`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)
        val event = note()
        var sends = 0

        val sent = sendSignedNote(event, repo, send = { if (sends++ == 0) 0 else 2 }, reconnect = { 1 }) { _, _ -> }

        assertEquals(2, sent)
        assertEquals(2, sends)
        assertEquals(listOf(event), seen)
    }

    @Test
    fun `a note no relay took is not announced, tracked or cached`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)
        val event = note()

        // Reconnect brought nothing back.
        assertEquals(0, sendSignedNote(event, repo, send = { 0 }, reconnect = { 0 }) { _, _ -> fail("tracked") })
        // Reconnect claimed a relay, the retry still reached none.
        assertEquals(0, sendSignedNote(event, repo, send = { 0 }, reconnect = { 1 }) { _, _ -> fail("tracked") })

        assertTrue(seen.isEmpty())
        assertNull(repo.getEvent(event.id))
    }

    // --- recipes ---

    @Test
    fun `a recipe that was signed and broadcast is announced once, after the broadcast`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)
        val broadcasts = mutableListOf<NostrEvent>()

        val event = signCacheAndBroadcast(signer, 30023, "# Soup", listOf(listOf("d", "soup")), repo) {
            assertTrue("announced before it was broadcast", seen.isEmpty())
            broadcasts += it
        }

        assertEquals(listOf(event), broadcasts)
        assertEquals(listOf(event), seen)
        assertNotNull(repo.getEvent(event.id))
    }

    @Test
    fun `a recipe whose signing failed is not announced`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)
        val refusing = object : NostrSigner by signer {
            override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent =
                throw IllegalStateException("signer said no")
        }

        val thrown = runCatching {
            signCacheAndBroadcast(refusing, 30023, "# Soup", emptyList(), repo) { fail("broadcast") }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a recipe whose broadcast threw is not announced`() = runTest {
        val repo = EventRepository()
        val seen = published(repo)

        val thrown = runCatching {
            signCacheAndBroadcast(signer, 30023, "# Soup", emptyList(), repo) { throw IllegalStateException("pool gone") }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(seen.isEmpty())
    }

    // --- where the signal can come from ---

    private val mainDir: File = sequenceOf("src/main/kotlin/cooking/zap/app", "app/src/main/kotlin/cooking/zap/app")
        .map(::File).first { it.isDirectory }

    @Test
    fun `only the note send and the recipe publish announce`() {
        val callers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "EventRepository.kt" }
            .filter { it.readText().contains("announcePublished(") }
            .map { it.name }.toSortedSet()
        assertEquals(sortedSetOf("NotePublish.kt", "RecipePublisher.kt"), callers)
        // Drafts, the scheduler client and the private-reply publisher name neither seam.
        for (file in mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            if (file.name in setOf("NotePublish.kt", "RecipePublisher.kt", "ComposeViewModel.kt")) continue
            val text = file.readText()
            assertTrue(file.name, !text.contains("sendSignedNote(") && !text.contains("signCacheAndBroadcast("))
        }
    }

    @Test
    fun `the composer reaches the note send only past the scheduled, private and PoW returns`() {
        val vm = File(mainDir, "viewmodel/ComposeViewModel.kt").readText()
        assertEquals(1, Regex("""\bsendSignedNote\(""").findAll(vm).count())
        val publishNote = vm.substring(
            vm.indexOf("private suspend fun publishNote("),
            vm.indexOf("private suspend fun scheduleViaApi("),
        )
        val call = publishNote.indexOf("sendSignedNote(")
        assertTrue("the one call is in publishNote", call > 0)
        for (earlier in listOf(
            "return publishPrivateReply(",       // private reply: gift-wrapped, returns
            "if (inputs.scheduleEnabled) {",     // scheduled: every branch inside returns
            "applyScheduleOptions(scheduleOptions().scheduleOff())",
            "powManager.submitNote(",            // PoW: mined and sent later by PowManager
            "return -1",
        )) {
            val at = publishNote.indexOf(earlier)
            assertTrue("$earlier comes before the send", at in 1 until call)
        }
        // Every exit of the scheduled block is a return: none falls through to the send.
        val scheduled = publishNote.substring(
            publishNote.indexOf("if (inputs.scheduleEnabled) {"),
            publishNote.indexOf("// Pubkeys whose inbox relays should also receive this note"),
        )
        assertTrue(scheduled.trimEnd().endsWith("return 1\n        }".trimEnd()))
    }
}
