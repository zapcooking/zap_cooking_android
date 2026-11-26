package cooking.zap.app.nostr

/**
 * Brainstorm's NIP-50 search relay — NosFabrica's `vespa-relay` at
 * `wss://search.brainstorm.world` — the primary search backend, replacing
 * Nostr Archives (unreliable, and under incident). Port of web
 * `nameSearch.ts`, from the same handoff
 * (zapcooking `docs/handoffs/name-search-brainstorm.md`), matching the iOS
 * `BrainstormSearch` port.
 *
 * Relay rules that shape this module (verified against the live relay,
 * 2026-11-17):
 *  - a NIP-50 search without `observer:<64-hex>` is refused (CLOSED,
 *    auth-required) — the relay ranks through a web of trust;
 *  - the observer token goes to THIS relay only. Another NIP-50 relay has
 *    no such extension and may match `observer:…` as literal words, which
 *    would empty the results — per-relay search strings are mandatory;
 *  - words shaped like `key:value` and a leading `-` are relay instructions
 *    (`observer:`, `sort:`, `include:`, exclusions) — strip them from what
 *    the user typed, or a user can change the viewpoint or lift the spam
 *    floor from the search box;
 *  - the house observer is the relay's own key, so nothing about the person
 *    typing — not even their public key — is tied to a search;
 *  - `limit` is not honored for short or prefix queries — slice results
 *    client-side;
 *  - authors below the relay's trust floor (and unscored accounts) do not
 *    appear. That is the deliberate trade for ranked search; do not lift it
 *    with `include:spam`.
 */
object BrainstormSearch {
    const val RELAY: String = "wss://search.brainstorm.world"

    /** The viewpoint the relay ranks through when the query names none — its
     *  own house key, so no user identity is ever attached to a search. */
    const val HOUSE_OBSERVER: String =
        "be7bf5de068c1d842ed34a7c270507ec940f5ea51671cfd062a95e9d09420d0a"

    /** The NIP-50 search-relay pair fanned for every full-text query:
     *  Brainstorm primary, nos.today kept behind it. Nostr Archives leaves
     *  the set (unreliable, then unreachable in an incident). */
    val DEFAULT_RELAYS: List<String> = listOf(RELAY, "wss://search.nos.today")

    /** What was typed, made safe to put in a NIP-50 search string: control
     *  characters become spaces, a leading `@` goes, words shaped like
     *  `key:value` or a leading `-` are relay instructions and drop,
     *  quote-only words drop. Capped at 64 chars like web. null when nothing
     *  searchable remains — ask no relay. */
    fun cleanQuery(raw: String?): String? {
        if (raw == null) return null
        val sanitized = buildString(raw.length) {
            for (ch in raw) append(if (ch.code < 0x20 || ch.code == 0x7f) ' ' else ch)
        }
        var s = sanitized
        while (s.startsWith("@")) s = s.drop(1)
        val kept = s.split(' ', '\t')
            .map { it.trim() }
            .filter { word ->
                word.isNotEmpty() &&
                    !word.startsWith("-") &&
                    !INSTRUCTION_WORD.containsMatchIn(word) &&
                    !QUOTE_ONLY_WORD.matches(word)
            }
        val capped = kept.joinToString(" ").take(64).trim()
        return capped.ifEmpty { null }
    }

    /** The search string for one relay: Brainstorm gets the cleaned text plus
     *  the house observer token; every other relay gets the cleaned text
     *  only. Never send the observer form to another relay. */
    fun searchQueryString(relayUrl: String, query: String): String? {
        val cleaned = cleanQuery(query) ?: return null
        return if (relayUrl == RELAY) "$cleaned observer:$HOUSE_OBSERVER" else cleaned
    }

    private val INSTRUCTION_WORD = Regex("^[a-z]+:\\S", RegexOption.IGNORE_CASE)
    private val QUOTE_ONLY_WORD = Regex("^[\"'*]+$")
}
