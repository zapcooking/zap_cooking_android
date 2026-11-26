package cooking.zap.app.fresh

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only smoke run of [FreshRelayClient] against the real
 * `wss://feed.zap.cooking`. **Skipped unless `FRESH_RELAY_LIVE=1`**, so CI
 * and ordinary local runs never touch the network:
 *
 * ```
 * FRESH_RELAY_LIVE=1 ./gradlew testZapstoreDebugUnitTest \
 *     --tests 'cooking.zap.app.fresh.FreshRelayClientLiveTest' --rerun
 * ```
 *
 * No key exists in this test. A recording transport wraps the production one
 * and the test fails if any frame other than `REQ` / `CLOSE` goes out. It
 * prints what the relay did (`FRESH-LIVE` lines) for the PR. Port of iOS
 * `FreshRelayClientLiveTests`.
 */
class FreshRelayClientLiveTest {

    @Test
    fun smoke() = runBlocking {
        assumeTrue("set $ENV_FLAG=1 to run the live feed relay check", System.getenv(ENV_FLAG) == "1")

        val transport = RecordingTransport(OkHttpFreshSocketTransport())
        val client = FreshRelayClient(transport = transport, silenceTimeoutMs = 10_000)
        fun log(line: String) = println("FRESH-LIVE $line")

        val page1 = client.feedPage()
        log("page1 outcome=${page1.outcome} events=${page1.events.size} received=${page1.received} rejected=${page1.rejected} short=${page1.isShortPage}")
        assertEquals(FreshOutcome.Answered, page1.outcome)
        assertEquals(0, page1.rejected)
        val kinds = page1.events.map { it.kind }.toSortedSet()
        log("page1 kinds=$kinds")
        assertTrue(FreshRelayClient.KINDS.containsAll(kinds))

        var until = page1.oldestCreatedAt
        var pages = 1
        var total = page1.events.size
        var last = page1
        while (until != null && !last.isShortPage && pages < 40) {
            last = client.feedPage(until = until)
            pages++
            total += last.events.size
            if (last.outcome != FreshOutcome.Answered) break
            until = last.oldestCreatedAt ?: until
        }
        log("walk pages=$pages events=$total lastOutcome=${last.outcome} lastShort=${last.isShortPage} rejected=${last.rejected}")
        assertTrue(last.outcome == FreshOutcome.Answered || last.outcome == FreshOutcome.Floor)

        val below = client.feedPage(until = client.floor - 1)
        log("belowFloor outcome=${below.outcome} (not sent)")
        assertEquals(FreshOutcome.Floor, below.outcome)

        val preview = client.topicPreview("pasta")
        log("preview pasta outcome=${preview.outcome} events=${preview.events.size}")
        assertEquals(FreshOutcome.Answered, preview.outcome)
        assertTrue(preview.events.size <= FreshRelayClient.PREVIEW_LIMIT)

        val unknown = client.topicPreview("no-such-topic-android-smoke")
        log("preview unknown outcome=${unknown.outcome}")
        assertTrue("unknown slug: ${unknown.outcome}", unknown.outcome is FreshOutcome.Invalid)
        val sentBefore = transport.sent.size
        val again = client.topicPreview("no-such-topic-android-smoke")
        log("preview unknown again outcome=${again.outcome} framesSent=${transport.sent.size - sentBefore}")
        assertEquals(sentBefore, transport.sent.size)

        val recipes = client.recipes()
        log("recipes outcome=${recipes.outcome} events=${recipes.events.size} rejected=${recipes.rejected} oldest=${recipes.events.lastOrNull()?.created_at ?: "-"}")
        assertEquals(FreshOutcome.Answered, recipes.outcome)

        val tail = launch { client.liveTail(System.currentTimeMillis() / 1000).collect {} }
        delay(3_000)
        log("tail lost=${client.tailIsLost()}")
        client.stopTail()
        tail.join()
        client.close()

        val sent = transport.sent.toList()
        val byPrefix = sent.groupingBy { it.take(8) }.eachCount()
        log("framesSent=${sent.size} byPrefix=$byPrefix authChallengesReceived=${transport.authFramesReceived.get()}")
        for (frame in sent) {
            assertTrue("sent ${frame.take(40)}", frame.startsWith("[\"REQ\"") || frame.startsWith("[\"CLOSE\""))
        }
    }

    /**
     * The production transport, recording every frame sent and counting the
     * AUTH frames received (which the client must ignore).
     */
    private class RecordingTransport(private val inner: FreshSocketTransport) : FreshSocketTransport {
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val authFramesReceived = AtomicInteger()

        override fun open(url: String, listener: FreshSocketListener): FreshSocket {
            val socket = inner.open(url, object : FreshSocketListener {
                override fun onText(text: String) {
                    if (text.startsWith("[\"AUTH\"")) authFramesReceived.incrementAndGet()
                    listener.onText(text)
                }

                override fun onClosed() = listener.onClosed()
            })
            return object : FreshSocket {
                override fun send(text: String): Boolean {
                    sent.add(text)
                    return socket.send(text)
                }

                override fun cancel() = socket.cancel()
            }
        }
    }

    private companion object {
        const val ENV_FLAG = "FRESH_RELAY_LIVE"
    }
}
