package cooking.zap.app.lazarus

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RelayMessage
import cooking.zap.app.relay.HttpClientFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lazarus's own relay connections: one per relay, reused across the pages,
 * retries and publish of a recovery session, and all released by [close]
 * when the recovery screen leaves. Kept apart from the app's pool, whose
 * cross-relay dedup would erase the found-on record and whose reconnect loop
 * would turn a dead relay into a long wait. Each connection is opened on the
 * requesting thread and never reconnects, so closing the transport closes
 * every socket it ever opened, even one still connecting.
 *
 * Each request reports how it ended (spec "Relay outcomes"): EOSE is the only
 * answer; a connection that couldn't open or dropped, or a NIP-01 CLOSED
 * (such as NIP-42's auth-required:), is a failure; no EOSE within the
 * timeout is a timeout. Frames reach a request in the order the relay sent
 * them, the drop included, so events that arrived before a failure or
 * timeout are kept: they're real versions, even though that relay's history
 * is incomplete. AUTH challenges go unanswered, so an auth-required relay
 * counts as failed rather than prompting the signer mid-scan.
 */
class LazarusSocketTransport(
    private val client: OkHttpClient = HttpClientFactory.createRelayClient()
) : LazarusRelayTransport {

    private class Entry(val socket: LazarusSocket) {
        /** Requests using this connection; guarded by [lock]. */
        var users = 0

        /** Failed or timed out: discarded once its last user is done. */
        var stale = false
    }

    private val lock = Any()
    private val sockets = HashMap<String, Entry>()
    private val sequence = AtomicLong()
    private var closed = false

    override suspend fun query(url: String, filter: Filter, timeoutMs: Long): LazarusRelayAnswer =
        withContext(Dispatchers.IO) {
            val entry = acquire(url)
                ?: return@withContext LazarusRelayAnswer(emptyList(), LazarusRelayOutcome.FAILED)
            val socket = entry.socket
            val subId = "laz-${sequence.incrementAndGet()}"
            val events = Collections.synchronizedList(mutableListOf<NostrEvent>())
            val ended = CompletableDeferred<LazarusRelayOutcome>()
            val outcome = exchange(socket, ClientMessage.req(subId, filter), timeoutMs, ended, LazarusRelayOutcome.FAILED) { msg ->
                when (msg) {
                    is RelayMessage.EventMsg -> if (msg.subscriptionId == subId) events.add(msg.event)
                    is RelayMessage.Eose -> if (msg.subscriptionId == subId) ended.complete(LazarusRelayOutcome.ANSWERED)
                    is RelayMessage.Closed -> if (msg.subscriptionId == subId) ended.complete(LazarusRelayOutcome.FAILED)
                    else -> {}
                }
            } ?: LazarusRelayOutcome.TIMED_OUT
            // Don't leave a slow relay subscribed after the scan moves on
            if (socket.isOpen) socket.send(ClientMessage.close(subId))
            release(url, entry, healthy = outcome != LazarusRelayOutcome.TIMED_OUT && !socket.isClosed)
            LazarusRelayAnswer(events.toList(), outcome)
        }

    override suspend fun publish(url: String, event: NostrEvent, timeoutMs: Long): LazarusPublishAnswer =
        withContext(Dispatchers.IO) {
            val entry = acquire(url) ?: return@withContext LazarusPublishAnswer(accepted = false)
            val socket = entry.socket
            val ended = CompletableDeferred<LazarusPublishAnswer>()
            val answer = exchange(socket, ClientMessage.event(event), timeoutMs, ended, LazarusPublishAnswer(accepted = false)) { msg ->
                if (msg is RelayMessage.Ok && msg.eventId == event.id) {
                    ended.complete(LazarusPublishAnswer(msg.accepted, msg.message.ifBlank { null }))
                }
            }
            release(url, entry, healthy = answer != null && !socket.isClosed)
            answer ?: LazarusPublishAnswer(accepted = false)
        }

    /**
     * Send [frame] on [socket] and wait until [ended] completes from
     * [handle], the connection fails or drops ([onDrop]), or [timeoutMs]
     * passes (null). The request listens before the frame goes out, so a fast
     * relay's first frames aren't missed; OkHttp queues the frame until the
     * socket opens.
     */
    private suspend fun <T> exchange(
        socket: LazarusSocket,
        frame: String,
        timeoutMs: Long,
        ended: CompletableDeferred<T>,
        onDrop: T,
        handle: (RelayMessage) -> Unit
    ): T? {
        val stop = socket.listen(object : LazarusSocket.Listener {
            override fun onMessage(message: RelayMessage) = handle(message)
            override fun onClosed() {
                ended.complete(onDrop)
            }
        })
        try {
            socket.send(frame)
            return withTimeoutOrNull(timeoutMs) { ended.await() }
        } finally {
            stop()
        }
    }

    /** This relay's connection: the open (or opening) one, or a fresh one. Null for anything that isn't a relay URL. */
    private fun acquire(url: String): Entry? {
        synchronized(lock) {
            if (closed) return null
            sockets[url]?.let { existing ->
                if (!existing.stale && !existing.socket.isClosed) {
                    existing.users++
                    return existing
                }
                if (existing.users == 0) existing.socket.close()
            }
        }
        // Opened outside the lock: newWebSocket can block briefly under contention
        val fresh = LazarusSocket.open(url, client) ?: return null
        synchronized(lock) {
            if (closed) {
                fresh.close()
                return null
            }
            val raced = sockets[url]
            if (raced != null && !raced.stale && !raced.socket.isClosed) {
                fresh.close()
                raced.users++
                return raced
            }
            return Entry(fresh).also {
                it.users = 1
                sockets[url] = it
            }
        }
    }

    private fun release(url: String, entry: Entry, healthy: Boolean) = synchronized(lock) {
        entry.users--
        if (!healthy) entry.stale = true
        if (entry.stale && entry.users == 0) {
            if (sockets[url] === entry) sockets.remove(url)
            entry.socket.close()
        }
    }

    override fun close() {
        val open = synchronized(lock) {
            closed = true
            sockets.values.toList().also { sockets.clear() }
        }
        open.forEach { it.socket.close() }
    }
}

/**
 * One websocket to one relay. Unlike the app's pooled relay connection it
 * never reconnects: once it fails or closes it stays closed, and a later
 * request opens a new one. Listeners get the relay's frames, then the close,
 * in order, on OkHttp's reader thread.
 */
private class LazarusSocket private constructor() {
    enum class State { CONNECTING, OPEN, CLOSED }

    interface Listener {
        fun onMessage(message: RelayMessage)
        fun onClosed()
    }

    private val state = AtomicReference(State.CONNECTING)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private lateinit var webSocket: WebSocket

    val isOpen: Boolean get() = state.get() == State.OPEN
    val isClosed: Boolean get() = state.get() == State.CLOSED

    /** Listen until the returned function is called; a socket that already closed reports it at once. */
    fun listen(listener: Listener): () -> Unit {
        listeners.add(listener)
        if (isClosed) listener.onClosed()
        return { listeners.remove(listener) }
    }

    fun send(text: String): Boolean = webSocket.send(text)

    fun close() {
        markClosed()
        webSocket.cancel()
    }

    private fun markClosed() {
        if (state.getAndSet(State.CLOSED) != State.CLOSED) listeners.forEach { it.onClosed() }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            state.compareAndSet(State.CONNECTING, State.OPEN)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = RelayMessage.parse(text) ?: return
            listeners.forEach { it.onMessage(message) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            markClosed()
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = markClosed()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = markClosed()
    }

    companion object {
        private const val USER_AGENT = "ZapCooking/1.0 (Android; Nostr)"

        /** Start connecting to [url]; null when it isn't a URL OkHttp can open a websocket to. */
        fun open(url: String, client: OkHttpClient): LazarusSocket? {
            val request = try {
                Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            } catch (_: IllegalArgumentException) {
                return null
            }
            return LazarusSocket().apply { webSocket = client.newWebSocket(request, socketListener) }
        }
    }
}
