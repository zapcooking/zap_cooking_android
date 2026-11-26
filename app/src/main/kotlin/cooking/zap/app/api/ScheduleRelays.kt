package cooking.zap.app.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The `relays` field of `POST /api/schedule`: which of the author's own
 * write relays to hand the scheduler.
 *
 * A Kotlin port of the web client's `pickScheduleRelays`
 * (zapcooking/frontend `src/lib/scheduleClient.ts`) and of the server's
 * own acceptance check (`isPublicRelayUrl` in `src/lib/live/endedEvent.ts`,
 * `normalizeScheduleRelay` in `src/lib/scheduleRelays.ts`). The server
 * rejects the whole request for ONE bad entry, so filtering here is not
 * optional — and both clients must agree on the same inputs, which is why
 * `ScheduleRelaysTest` carries web's test cases.
 */
object ScheduleRelays {
    /** Contract: at most 4 after duplicates are folded. */
    const val MAX_RELAYS = 4

    /** Contract: each URL under 200 characters. */
    const val MAX_URL_LENGTH = 200

    /**
     * Relays that never store a note (purplepag.es holds only profiles and
     * relay lists). Copied from `ENDED_HOST_RELAYS_NEVER` in
     * zapcooking/frontend `src/lib/live/endedEvent.ts` — keep the two in step.
     */
    val RELAYS_NEVER: List<String> = listOf("wss://purplepag.es")

    /**
     * Relays not worth one of the four places: the two the scheduler always
     * publishes to anyway, and [RELAYS_NEVER]. If the scheduler's fixed list
     * changes, this only costs a wasted place, never a missed relay.
     */
    val RELAYS_NOT_WORTH_SENDING: List<String> =
        listOf("wss://nos.lol", "wss://relay.primal.net") + RELAYS_NEVER

    /** `PRIVATE_SUFFIXES` in `src/lib/live/endedEvent.ts`. */
    private val PRIVATE_SUFFIXES = listOf(
        ".local", ".localhost", ".localdomain", ".internal", ".intranet", ".private", ".corp",
        ".home", ".lan", ".home.arpa", ".test", ".example", ".invalid", ".onion", ".i2p",
    )

    private val NUMERIC_LABEL = Regex("^(\\d+|0x[0-9a-f]*)$")
    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    /**
     * Parse a `wss:` URL. OkHttp only parses http(s), and for a host,
     * credentials, port, path and query the two schemes parse alike (both
     * default to 443), so the scheme is swapped for parsing only.
     */
    private fun parse(value: String): HttpUrl? {
        if (!value.startsWith("wss:", ignoreCase = true)) return null
        return ("https:" + value.substring(4)).toHttpUrlOrNull()
    }

    /**
     * Would the server open a connection to this relay? `wss://` only, a
     * public hostname (no IP literal of either family, no localhost,
     * nothing that names a private network, at least one dot), no
     * credentials, no whitespace, at most 200 characters.
     */
    fun isPublicRelayUrl(value: String?): Boolean {
        if (value == null || value.length > MAX_URL_LENGTH || value.any { it.isWhitespace() }) return false
        val url = parse(value) ?: return false
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()) return false
        val host = url.host.lowercase().removeSuffix(".")
        if (host.isEmpty() || host.startsWith("[") || host.contains(':')) return false // IPv6 literal
        val labels = host.split('.')
        if (labels.size < 2) return false // localhost, single-label names
        // IPv4 in any spelling: no all-numeric (or 0x…) last label.
        if (NUMERIC_LABEL.matches(labels.last())) return false
        if (!labels.all { HOST_LABEL.matches(it) }) return false
        if (PRIVATE_SUFFIXES.any { suffix -> host == suffix.substring(1) || host.endsWith(suffix) }) return false
        return true
    }

    /**
     * Canonical spelling, as the server stores and compares it: lowercase
     * scheme and host, no default port, no trailing slash, no fragment. The
     * path and query are kept as sent. Call only on a URL that passed
     * [isPublicRelayUrl].
     */
    fun normalize(value: String): String {
        val url = requireNotNull(parse(value)) { "not a wss URL" }
        // The host is kept as parsed (a trailing dot included), like JS `URL.host`.
        val port = if (url.port != 443) ":${url.port}" else ""
        val path = url.encodedPath.trimEnd('/')
        val query = url.encodedQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
        return "wss://${url.host.lowercase()}$port$path$query"
    }

    /**
     * Turn a relay list (the author's NIP-65 write relays, in their order)
     * into a `relays` value the server will accept: only URLs that pass
     * its check, normalized, without duplicates or
     * [RELAYS_NOT_WORTH_SENDING], at most [MAX_RELAYS]. Order is kept. An
     * empty result means "omit the field".
     */
    fun pick(urls: List<String?>): List<String> {
        val skip = RELAYS_NOT_WORTH_SENDING.map(::normalize).toSet()
        val out = ArrayList<String>(MAX_RELAYS)
        for (url in urls) {
            if (out.size >= MAX_RELAYS) break
            if (url == null || url.length >= MAX_URL_LENGTH) continue
            if (!isPublicRelayUrl(url)) continue
            val normalized = normalize(url)
            if (normalized in skip || normalized in out) continue
            out.add(normalized)
        }
        return out
    }
}
