package cooking.zap.app.fresh

import cooking.zap.app.FeatureFlags

/**
 * The curated feed relay (`wss://feed.zap.cooking`, build spec §1 "Fresh feed
 * relay"). Port of iOS `FeedRelay.swift` + `Nip42.loginAllowed`.
 *
 * The one fact about it every relay path needs: is this URL that relay?
 * [loginAllowed] asks, so no socket in the app answers its NIP-42 challenge
 * while [FeatureFlags.FEED_RELAY_LOGIN_ENABLED] is off. The relay sends a
 * challenge on connect and again before every `CLOSED auth-required:`; a login
 * tells it who is reading.
 */
object FeedRelay {
    const val URL = "wss://feed.zap.cooking"
    const val HOST = "feed.zap.cooking"

    /**
     * True when [raw] names the feed relay's host, however it is spelled: any
     * scheme or none, any case, a port, a path, a query, user info, a trailing
     * slash or a trailing root dot. Matched on the host alone because a relay
     * URL reaches the signing paths from many places (the relay picker, relay
     * lists, hints) and none of them agree on a canonical form. Subdomains and
     * look-alike hosts do not match.
     */
    fun isFeedRelay(raw: String): Boolean {
        var rest = raw.trim().lowercase()
        val scheme = rest.indexOf("://")
        if (scheme >= 0) rest = rest.substring(scheme + 3)
        // Authority ends at the first path, query or fragment delimiter.
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) rest = rest.substring(0, end)
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        val colon = rest.indexOf(':')
        if (colon >= 0) rest = rest.substring(0, colon)
        return rest.trimEnd('.') == HOST
    }

    /**
     * May the app answer a NIP-42 challenge from [relayUrl]? Everything but
     * the feed relay: yes. The feed relay: only when login is enabled.
     */
    fun loginAllowed(
        relayUrl: String,
        feedRelayLoginEnabled: Boolean = FeatureFlags.FEED_RELAY_LOGIN_ENABLED,
    ): Boolean = feedRelayLoginEnabled || !isFeedRelay(relayUrl)
}
