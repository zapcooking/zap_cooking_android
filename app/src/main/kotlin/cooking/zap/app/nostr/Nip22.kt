package cooking.zap.app.nostr

import java.net.URI

/**
 * NIP-22 comments (kind 1111).
 *
 * A comment is always scoped to a root — either a nostr event (uppercase `E`/`A`
 * tags) or an external identifier (an uppercase `I` tag, per NIP-73: a URL,
 * podcast GUID, geohash, ISBN…). Uppercase tags name the *root* scope; lowercase
 * tags name the *immediate parent*; so a top-level comment repeats the same
 * value in both.
 *
 * Only the external-root case is rendered specially: a comment rooted on a nostr
 * event already shows its parent inline, so it needs no extra treatment. See
 * [externalRoot] and [ExternalRef].
 *
 * The object also carries the mixed-thread plumbing: [rootEventId] re-roots a
 * thread opened on a comment at its conversation root, [threadsOffRoot] is the
 * thread-screen ingest guard for both threading systems, and [buildReplyTags]
 * derives the kind-1111 reply tag set (the single source for both the reply-kind
 * decision and the tags so they can never disagree).
 */
object Nip22 {
    const val KIND_COMMENT = 1111

    /**
     * The external thing a comment is scoped to, when the scope isn't a nostr
     * event. [kind] is NIP-73's identifier type (`web`, `podcast:item:guid`,
     * `isbn`, …); [value] is the identifier itself; [hint] is the tag's optional
     * third position — for non-URL identifiers this is where a human-openable
     * page lives (e.g. a podcast GUID pointing at its episode page).
     */
    data class ExternalRef(
        val value: String,
        val kind: String,
        val hint: String?
    ) {
        /**
         * The URI a "view the original" affordance should open, if any. Prefers
         * the hint, since for non-`web` kinds the value itself isn't openable
         * (`podcast:item:guid:…` is an identifier, not a link). Only `http(s)`,
         * so a `javascript:` value in an `I` tag can't become a tappable link.
         */
        val openableUri: URI?
            get() {
                hint?.let { h -> openableHttp(h)?.let { return it } }
                if (kind == "web") return openableHttp(value)
                return null
            }

        /** Host shown as the source label, e.g. "bitcoinmagazine.com" (strips `www.`). */
        val displayHost: String?
            get() = openableUri?.host?.removePrefix("www.")

        private fun openableHttp(raw: String): URI? =
            runCatching { URI(raw) }.getOrNull()?.let { u ->
                if ((u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()) u else null
            }
    }

    /**
     * A nostr event a comment is scoped to (the comment's subject) — either by id
     * (uppercase `E`) or by addressable coordinate (uppercase `A`,
     * `kind:pubkey:dTag`). See [eventRoot].
     */
    sealed class EventRootRef {
        /** Root named by event id. [relayHint]/[authorPubkey] from the tag if present. */
        data class ById(
            val id: String,
            val relayHint: String?,
            val authorPubkey: String?
        ) : EventRootRef()

        /** Root named by addressable coordinate (`kind:pubkey:dTag`). */
        data class Addressable(
            val kind: Int,
            val pubkey: String,
            val dTag: String,
            val relayHint: String?
        ) : EventRootRef()
    }

    fun isComment(event: NostrEvent): Boolean = event.kind == KIND_COMMENT

    // MARK: - Event-rooted comments

    // A comment can be scoped to a nostr event instead of an external
    // identifier, and in the wild that root is very often a plain kind-1
    // note: a thread starts in NIP-10 and a participant's client switches to
    // comments partway down, carrying `E` = the kind-1 root with `K` = "1".

    /**
     * The event this comment is rooted on (uppercase `E`), or null when the
     * root is external (`I`) or addressable-only (`A`).
     */
    fun rootEventId(event: NostrEvent): String? = tagValue(event, "E")

    /**
     * The kind of the root the comment is scoped to (uppercase `K`). A string
     * on the wire, because an external root names a NIP-73 type (`web`,
     * `podcast:item:guid`) rather than a number.
     */
    fun rootKindRaw(event: NostrEvent): String? = tagValue(event, "K")

    /** Author of the root event (uppercase `P`). */
    fun rootAuthor(event: NostrEvent): String? = tagValue(event, "P")

    /**
     * The comment's immediate parent event (lowercase `e`). Equals the root for
     * a top-level comment; points at another comment further down.
     */
    fun parentEventId(event: NostrEvent): String? = tagValue(event, "e")

    /**
     * The immediate parent's kind (lowercase `k`) — the tag that decides
     * whether someone answered a note or another comment.
     */
    fun parentKindRaw(event: NostrEvent): String? = tagValue(event, "k")

    /** The immediate parent's kind as an integer, or null when the parent is external (`k` = "web"). */
    fun parentKind(event: NostrEvent): Int? = parentKindRaw(event)?.toIntOrNull()

    /** Author of the immediate parent (lowercase `p`). */
    fun parentAuthor(event: NostrEvent): String? = tagValue(event, "p")

    /**
     * First value of the first tag with this exact name. Case matters — `E` and
     * `e` mean different things in this NIP, so this deliberately does not fold case.
     */
    private fun tagValue(event: NostrEvent, name: String): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name && it.getOrNull(1)?.isNotEmpty() == true }
            ?.getOrNull(1)

    /**
     * Whether [event] belongs on the thread screen rooted at [targets] — the
     * thread's root id and/or focal id, plus any NIP-22 comment anchors.
     *
     * The two threading systems answer this differently. A kind-1 NIP-10 reply
     * carries the conversation root in a lowercase `e` tag, so a `#e = root`
     * filter reaches its whole tree. A NIP-22 comment carries only its
     * *immediate parent* in lowercase `e` and names the root in uppercase `E` —
     * so a comment-to-comment reply matches no lowercase check even though it
     * hangs off the same root. Thread display and the live reply stream must
     * accept both forms.
     */
    fun threadsOffRoot(event: NostrEvent, targets: Set<String>): Boolean {
        val parentEventIds = mutableListOf<String>()
        val rootEventIds = mutableListOf<String>()
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "e" -> parentEventIds.add(tag[1])
                "E" -> rootEventIds.add(tag[1])
            }
        }
        return parentEventIds.any { it in targets } || rootEventIds.any { it in targets }
    }

    /**
     * The comment's root scope when it's a nostr event — an uppercase `E` tag
     * (by id) or `A` tag (addressable `kind:pubkey:dTag`). Returns null for
     * externally-rooted comments (`I` tag) and non-comments. Returns the root
     * whatever its kind; callers decide which kinds they render (the profile
     * Comments tab shows a subject card only for addressable kind 30023).
     */
    fun eventRoot(event: NostrEvent): EventRootRef? {
        if (!isComment(event)) return null
        // Uppercase E (by id) takes precedence over A (addressable) when both exist.
        val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "E" }
        if (eTag != null) {
            return EventRootRef.ById(
                id = eTag[1],
                relayHint = eTag.getOrNull(2)?.takeIf { it.isNotEmpty() },
                authorPubkey = eTag.getOrNull(3)?.takeIf { it.isNotEmpty() },
            )
        }
        val aTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "A" } ?: return null
        // `kind:pubkey:dTag` — drop(2).joinToString(":") so a dTag containing ":"
        // (common in some identifier schemes) survives the split.
        val parts = aTag[1].split(":")
        if (parts.size < 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        val pubkey = parts[1]
        val dTag = parts.drop(2).joinToString(":")
        return EventRootRef.Addressable(
            kind = kind,
            pubkey = pubkey,
            dTag = dTag,
            relayHint = aTag.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The comment's root scope when it's external (an uppercase `I` tag), else null.
     * Returns null for comments rooted on a nostr event (uppercase `E`/`A`) — those
     * already render with their parent inline, so they need no extra treatment.
     */
    fun externalRoot(event: NostrEvent): ExternalRef? {
        if (!isComment(event)) return null
        // Only treat I as the root when no uppercase E/A root is present, matching
        // the spec's "root scope" exclusivity.
        val hasEventRoot = event.tags.any { it.isNotEmpty() && (it[0] == "E" || it[0] == "A") }
        if (hasEventRoot) return null
        val iTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "I" } ?: return null
        val kind = event.tags.firstOrNull { it.size >= 2 && it[0] == "K" }?.get(1) ?: "web"
        val hint = iTag.takeIf { it.size >= 3 && it[2].isNotEmpty() }?.get(2)
        return ExternalRef(value = iTag[1], kind = kind, hint = hint)
    }

    /**
     * The comment's immediate parent when it's external (a lowercase `i` tag).
     * Equals the root for a top-level comment; differs when replying to another
     * comment on the same external item.
     */
    fun externalParent(event: NostrEvent): ExternalRef? {
        if (!isComment(event)) return null
        val hasEventParent = event.tags.any { it.isNotEmpty() && (it[0] == "e" || it[0] == "a") }
        if (hasEventParent) return null
        val iTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "i" } ?: return null
        val kind = event.tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1) ?: "web"
        val hint = iTag.takeIf { it.size >= 3 && it[2].isNotEmpty() }?.get(2)
        return ExternalRef(value = iTag[1], kind = kind, hint = hint)
    }

    /**
     * Build the tag set for a kind-1111 reply to [parent], carrying its root
     * scope forward unchanged and pointing the lowercase tags at [parent].
     *
     * Any comment parent qualifies — externally rooted (`I`) or event-rooted
     * (`E`/`A`). NIP-22 forbids answering a comment with a kind-1: the root
     * scope has to survive the hop, and a kind-1's NIP-10 `e` tags can neither
     * express an `I` root nor stay visible to `#E` readers, which is how a
     * branch silently drops out of every comment-aware client. The uppercase
     * scope is copied verbatim from the parent (the same thing Ditto does in
     * `usePostComment`), the lowercase side points at the parent event.
     * Returns null when [parent] isn't a comment carrying a root scope —
     * callers fall back to NIP-10 kind-1 threading for plain notes.
     */
    fun buildReplyTags(parent: NostrEvent, relayHint: String = ""): List<List<String>>? {
        if (!isComment(parent)) return null
        // Copy the root scope verbatim, one tag per name: `E`/`A`/`I` name the
        // root, `K` its kind, `P` its author. A parent without any of E/A/I is
        // malformed — the reply would be unscoped and unthreadable, so refuse.
        val rootScopeNames = setOf("E", "A", "I", "K", "P")
        val seenNames = mutableSetOf<String>()
        val rootScope = parent.tags.filter { tag ->
            if (tag.size < 2 || tag[1].isEmpty()) return@filter false
            val name = tag[0]
            name in rootScopeNames && seenNames.add(name)
        }
        if (rootScope.none { it[0] == "E" || it[0] == "A" || it[0] == "I" }) return null

        val tags = rootScope.toMutableList()
        // Parent is the comment itself — an event — so the lowercase side uses
        // e/k/p regardless of which form the root scope takes.
        tags.add(listOf("e", parent.id, relayHint, parent.pubkey))
        tags.add(listOf("k", KIND_COMMENT.toString()))
        tags.add(listOf("p", parent.pubkey))
        return tags
    }
}
