package cooking.zap.app.lazarus

import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted relay network for the Lazarus engine and publisher tests: each
 * query and publish is answered by [answer] / [accept] per relay, and every
 * request is recorded. Stands in for [LazarusSocketTransport], so the relay
 * orchestration runs on the JVM without sockets.
 */
internal class FakeTransport(private val delayMs: Long = 0) : LazarusRelayTransport {
    val queries: MutableList<Pair<String, Filter>> = Collections.synchronizedList(mutableListOf())
    val published: MutableList<Pair<String, NostrEvent>> = Collections.synchronizedList(mutableListOf())
    @Volatile var closed = false

    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()

    var answer: (url: String, filter: Filter) -> LazarusRelayAnswer = { _, _ -> answered() }
    var accept: (url: String, event: NostrEvent) -> LazarusPublishAnswer = { _, _ -> LazarusPublishAnswer(accepted = true) }

    override suspend fun query(url: String, filter: Filter, timeoutMs: Long): LazarusRelayAnswer {
        queries.add(url to filter)
        val now = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            if (delayMs > 0) delay(delayMs)
            return answer(url, filter)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun publish(url: String, event: NostrEvent, timeoutMs: Long): LazarusPublishAnswer {
        published.add(url to event)
        return accept(url, event)
    }

    override fun close() {
        closed = true
    }

    fun queriedUrls(): List<String> = synchronized(queries) { queries.map { it.first } }
    fun publishedUrls(): List<String> = synchronized(published) { published.map { it.first } }
}

internal fun answered(vararg events: NostrEvent) = LazarusRelayAnswer(events.toList(), LazarusRelayOutcome.ANSWERED)
internal fun failed(vararg events: NostrEvent) = LazarusRelayAnswer(events.toList(), LazarusRelayOutcome.FAILED)
internal fun timedOut(vararg events: NostrEvent) = LazarusRelayAnswer(events.toList(), LazarusRelayOutcome.TIMED_OUT)

/** Serve a fixed history from [relay] (every other relay answers empty), honoring `until` and `limit` unless told not to. */
internal fun serving(
    relay: String,
    events: List<NostrEvent>,
    honorUntil: Boolean = true
): (String, Filter) -> LazarusRelayAnswer = { url, filter ->
    if (url != relay) answered()
    else LazarusRelayAnswer(
        events
            .filter { !honorUntil || filter.until == null || it.created_at <= filter.until }
            .sortedByDescending { it.created_at }
            .take(filter.limit ?: Lazarus.SCAN_LIMIT),
        LazarusRelayOutcome.ANSWERED
    )
}

/** The tests' signature check: everything verifies except events signed "forged". */
internal val fakeVerify: (NostrEvent) -> Boolean = { it.sig != "forged" }

internal fun testEvent(
    id: String,
    createdAt: Long,
    kind: Int = 3,
    pubkey: String = "test-pubkey",
    tags: List<List<String>> = emptyList(),
    content: String = "",
    sig: String = "sig"
) = NostrEvent(
    id = id.padStart(64, '0'),
    pubkey = pubkey,
    created_at = createdAt,
    kind = kind,
    tags = tags,
    content = content,
    sig = sig
)

internal fun followTags(count: Int): List<List<String>> =
    (0 until count).map { listOf("p", it.toString(16).padStart(64, '0')) }

/**
 * A signer that signs whatever it's asked, as [signAs], with a fake
 * signature. [mutate] alters the returned event, [fail] makes it throw, and
 * [onSign] runs mid-approval (to switch accounts, say).
 */
internal class FakeSigner(
    override val pubkeyHex: String,
    var signAs: String = pubkeyHex,
    var mutate: (NostrEvent) -> NostrEvent = { it },
    var fail: Exception? = null,
    var onSign: () -> Unit = {}
) : NostrSigner {
    var signed = 0

    override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        fail?.let { throw it }
        signed += 1
        onSign()
        return mutate(
            NostrEvent(
                id = "signed-$createdAt".padEnd(64, '0'),
                pubkey = signAs,
                created_at = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = "sig"
            )
        )
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String = error("unused")
    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String = error("unused")
}
