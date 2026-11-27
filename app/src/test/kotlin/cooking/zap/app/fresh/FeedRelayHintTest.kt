package cooking.zap.app.fresh

import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.repo.EventRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rider b: the feed relay never becomes a relay hint (build spec §1).
 *
 * Found: [EventRepository.cacheEvent] takes the event alone and records no
 * source relay. Source attribution is written only by
 * [EventRepository.addEventRelay] and `RelayHintStore.addHint`, both fed by
 * shared-pool deliveries and by the relay field of `e`/`p` tags inside
 * events. Fresh calls neither. The feed relay's URL could still reach them
 * from outside Fresh (a tag in someone's post naming it, or a pool socket
 * to it), so both refuse it at the door.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedRelayHintTest {

    private val mainDir: File = sequenceOf("src/main/kotlin/cooking/zap/app", "app/src/main/kotlin/cooking/zap/app")
        .map(::File).first { it.isDirectory }

    private val spellings = listOf(
        "wss://feed.zap.cooking", "wss://feed.zap.cooking/", "WSS://Feed.Zap.Cooking", "ws://feed.zap.cooking:443/x",
        "feed.zap.cooking", "wss://feed.zap.cooking.",
    )

    @Test
    fun `the feed relay may not be a hint, however it is spelled, and every other relay may`() {
        for (url in spellings) assertFalse(url, FeedRelay.mayBeHint(url))
        for (url in listOf("wss://nos.lol", "wss://pantry.zap.cooking", "wss://zap.cooking", "wss://feed.zap.cooking.example.com")) {
            assertTrue(url, FeedRelay.mayBeHint(url))
        }
    }

    @Test
    fun `a Fresh post is cached with no source relay`() = runTest {
        val keys = Keys.generate()
        // A post whose own tags name the feed relay as a hint, as a client reading from it might write.
        val post = NostrEvent.create(
            privkey = keys.privkey, pubkey = keys.pubkey, kind = 1, content = "from the feed relay",
            tags = listOf(listOf("e", "a".repeat(64), FeedRelay.URL, "mention"), listOf("p", "b".repeat(64), FeedRelay.URL)),
            createdAt = NOW - 100,
        )
        val repo = EventRepository()
        val transport = FakeFreshTransport()
        val controller = FreshFeedController(
            pubkey = "c".repeat(64),
            client = FreshRelayClient(
                transport = transport, now = { NOW }, dispatcher = StandardTestDispatcher(testScheduler),
                monotonicMs = { testScheduler.currentTime },
            ),
            now = { NOW },
            // The live wiring's persist, exactly.
            persist = { events -> events.forEach(repo::cacheEvent) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.activate()
        runCurrent()
        assertEquals(FeedRelay.URL, transport.latest?.url)
        val req = Json.parseToJsonElement(transport.latest!!.sent.first()) as JsonArray
        val subId = req[1].jsonPrimitive.content
        transport.latest?.feed("""["EVENT","$subId",${post.toJson()}]""")
        transport.latest?.feed("""["EOSE","$subId"]""")
        runCurrent()
        assertEquals(listOf(post.id), controller.state.value.posts.map { it.id })

        // Cached, so a thread opens from it. In this JVM any use of the
        // source-relay record throws (android.util.LruCache is a stub: see
        // the next case), so getting here shows cacheEvent never touched it.
        assertNotNull(repo.getEvent(post.id))
        controller.close()
    }

    /**
     * android.util.LruCache is an unmocked stub in the hermetic suite: every
     * method on it throws. That makes the order checkable. A relay that may
     * be a hint reaches the record (and throws here); the feed relay is
     * turned away before the record is touched (and does not).
     */
    @Test
    fun `the source-relay record refuses the feed relay before it is touched`() {
        val repo = EventRepository()
        val id = "d".repeat(64)
        for (url in spellings) repo.addEventRelay(id, url)
        val reached = try {
            repo.addEventRelay(id, "wss://nos.lol")
            false
        } catch (e: RuntimeException) {
            e.message.orEmpty().contains("LruCache")
        }
        assertTrue("the control must reach the record, or the case above proves nothing", reached)
    }

    /** "Copy note ID" (PostCard) mints the nevent from the recorded relays: what the record refuses can't be in it. */
    @Test
    fun `an nevent minted for a Fresh post does not carry the feed relay URL`() {
        val keys = Keys.generate()
        val post = NostrEvent.create(keys.privkey, keys.pubkey, kind = 1, content = "x", tags = emptyList(), createdAt = NOW)
        // Everything that tried to record itself as this post's source.
        val offered = spellings.filter { it.startsWith("ws") } + "wss://nos.lol"
        val recorded = offered.filter(FeedRelay::mayBeHint)
        val nevent = Nip19.neventEncode(
            eventId = post.id.hexToByteArray(),
            relays = recorded.take(3),
            author = post.pubkey.hexToByteArray(),
        )
        val decoded = Nip19.neventDecode(nevent)
        assertEquals(post.id, decoded.eventId)
        assertEquals(listOf("wss://nos.lol"), decoded.relays)
        // And with nothing else recorded, which is the Fresh case: no relay at all.
        assertTrue(Nip19.neventDecode(Nip19.neventEncode(post.id.hexToByteArray(), emptyList(), post.pubkey.hexToByteArray())).relays.isEmpty())
        // The share sheet's nevent takes no relays in the first place.
        val card = File(mainDir, "ui/component/PostCard.kt").readText()
        assertTrue(card.contains("val relays = eventRepo?.getEventRelays(event.id)?.take(3)?.toList() ?: emptyList()"))
        assertTrue(card.contains("val nevent = Nip19.neventEncode(event.id.hexToByteArray())"))
    }

    @Test
    fun `both places a relay hint is recorded check the feed relay first`() {
        val repo = File(mainDir, "repo/EventRepository.kt").readText()
        val add = repo.substring(repo.indexOf("fun addEventRelay(eventId: String, relayUrl: String) {"), repo.indexOf("fun getEventRelays("))
        assertTrue(add.indexOf("if (!FeedRelay.mayBeHint(relayUrl)) return") in 0 until add.indexOf("eventRelays"))
        // getEventRelays and getRelayHintsForEvents read only what addEventRelay and the hint store wrote.
        assertEquals(1, Regex("""eventRelays\.put\(""").findAll(repo).count())

        val store = File(mainDir, "repo/RelayHintStore.kt").readText()
        val hint = store.substring(store.indexOf("fun addHint(pubkey: String, relayUrl: String) {"), store.indexOf("fun addAuthorRelay("))
        assertTrue(hint.indexOf("if (!FeedRelay.mayBeHint(relayUrl)) return") in 0 until hint.indexOf("cache.get(pubkey)"))
        // Every write to the hint cache goes through addHint, or the load from disk, which filters too.
        assertEquals(2, Regex("""cache\.put\(""").findAll(store).count())
        val load = store.substring(store.indexOf("private fun loadFromPrefs()"))
        assertTrue(load.contains("FeedRelay.mayBeHint(it)"))
    }

    @Test
    fun `Fresh never records where an event came from`() {
        for (name in listOf("fresh/FreshFeedController.kt", "fresh/FreshFeedWiring.kt", "fresh/FreshRelayClient.kt", "viewmodel/FeedViewModel.kt")) {
            val source = File(mainDir, name).readText()
            for (call in listOf("addEventRelay(", "addAuthorRelay(", "addHint(", "addHintRelays(")) {
                assertFalse("$name must not call $call", source.contains(call))
            }
        }
        // The controller's two outputs take events and nothing else.
        val controller = File(mainDir, "fresh/FreshFeedController.kt").readText()
        assertTrue(controller.contains("private val persist: (List<NostrEvent>) -> Unit"))
        assertTrue(controller.contains("private val onShown: (List<NostrEvent>) -> Unit"))
    }

    private companion object {
        const val NOW = 1_800_000_000L
    }
}
