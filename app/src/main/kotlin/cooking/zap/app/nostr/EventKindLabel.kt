package cooking.zap.app.nostr

/**
 * Sidecar-style kind label for the note details drawer: "KIND 1111 · COMMENT".
 * The number is always shown; known kinds get a human word after the separator,
 * unknown kinds degrade to just the number. Beside "Posted via <client>" this
 * answers "which kind was used by which client" (e.g. a Ditto reply reads
 * `KIND 1111 · COMMENT` + `Posted via Ditto`).
 *
 * Ports wisp-ios `EventKindLabel`.
 */
object EventKindLabel {
    fun label(kind: Int): String {
        val name = when (kind) {
            0 -> "PROFILE"
            1 -> "NOTE"
            3 -> "FOLLOWS"
            4 -> "DM"
            5 -> "DELETION"
            6 -> "REPOST"
            7 -> "REACTION"
            20 -> "PICTURE"
            21, 22 -> "VIDEO"
            1059 -> "GIFT WRAP"
            1068 -> "POLL"
            1111 -> "COMMENT"
            6969 -> "ZAP POLL"
            30023 -> "ARTICLE"
            30078 -> "APP DATA"
            else -> null
        }
        return if (name != null) "KIND $kind · $name" else "KIND $kind"
    }
}
