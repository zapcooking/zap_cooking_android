package cooking.zap.app.fresh

import cooking.zap.app.relay.HttpClientFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The seam between [FreshRelayClient] and a websocket (the Android equivalent
 * of the iOS `GroupRelayTransport`). It carries raw text frames, so the
 * client's own parser and verifier are what a test exercises; hermetic tests
 * script it and never open a socket.
 */
interface FreshSocketTransport {
    /**
     * Start connecting to [url]. Frames sent before the socket opens are
     * queued by the socket. [listener] may be called from any thread, and
     * from inside this call.
     */
    fun open(url: String, listener: FreshSocketListener): FreshSocket
}

interface FreshSocket {
    /** Queue one text frame; false when the socket is already closed. */
    fun send(text: String): Boolean

    /** Tear the connection down now. */
    fun cancel()
}

interface FreshSocketListener {
    /** One text frame from the relay, in the order the relay sent it. */
    fun onText(text: String)

    /** The connection failed, dropped or closed. Called once, after every [onText]. */
    fun onClosed()
}

/**
 * The production transport: one [OkHttpClient], built on first use and kept
 * for this transport's lifetime, and one websocket per [open]. A socket never
 * reconnects: once it reports [FreshSocketListener.onClosed] it stays closed,
 * and [FreshRelayClient] opens a new one with its next request.
 *
 * OkHttp sends a websocket ping every 30 s (a control frame, the relay's own
 * probe held a tail open the same way). The only text frames are the ones
 * [FreshRelayClient] sends.
 */
class OkHttpFreshSocketTransport(
    clientFactory: () -> OkHttpClient = { HttpClientFactory.createRelayClient() },
) : FreshSocketTransport {

    private val client: OkHttpClient by lazy(clientFactory)

    override fun open(url: String, listener: FreshSocketListener): FreshSocket {
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        } catch (_: IllegalArgumentException) {
            listener.onClosed()
            return ClosedSocket
        }
        val closed = AtomicBoolean(false)
        fun closedOnce() {
            if (closed.compareAndSet(false, true)) listener.onClosed()
        }
        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed.get()) listener.onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closedOnce()
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedOnce()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closedOnce()
        })
        return object : FreshSocket {
            override fun send(text: String): Boolean = webSocket.send(text)
            override fun cancel() = webSocket.cancel()
        }
    }

    private object ClosedSocket : FreshSocket {
        override fun send(text: String): Boolean = false
        override fun cancel() {}
    }

    private companion object {
        /** The header every relay socket in the app carries; it does not name the reader. */
        const val USER_AGENT = "ZapCooking/1.0 (Android; Nostr)"
    }
}
