package cooking.zap.app.lazarus

/**
 * Lazarus: recovery of user data from relay history.
 *
 * The kind registry is the single source of truth for how each recoverable
 * kind is counted, ranked, and warned about; the algorithm (scan / rank /
 * delta / recover) never hardcodes kind semantics, so new kinds are added
 * here and nowhere else.
 *
 * Kotlin port of the spec's reference registry (dmnyc/jumble-spark,
 * feat/lazarus-data-recovery; vendored by zapcooking/frontend#753), spec
 * 0.5.0-draft. When the spec reaches 1.0, re-vendor rather than hand-patch,
 * so conformance vectors and kind semantics stay in step.
 * Spec: https://github.com/dmnyc/lazarus/blob/main/SPEC.md
 */
enum class LazarusRanking { COUNT, RECENCY, INTENT }

enum class LazarusWarning { REMUTE, STALE_RELAYS, AFFECTS_OTHERS }

/** Inclusive estimate range for a count that can only be bracketed. */
data class LazarusCountRange(val min: Int, val max: Int)

data class LazarusItemCount(
    /** Number of publicly visible items. */
    val count: Int,
    /**
     * True when the event carries encrypted private items that were not
     * decrypted for this count, so the public count may understate the real
     * size of the list.
     */
    val partial: Boolean,
    /** Private items, once the encrypted content has been decrypted. */
    val privateCount: Int? = null,
    /**
     * Private items estimated from the encrypted payload size, while they
     * haven't been decrypted. Enough to tell an emptied private list from a
     * full one, which the public count alone reads as the same zero.
     */
    val privateEstimate: LazarusCountRange? = null
)

data class LazarusKindProfile(
    val kind: Int,
    val name: String,
    val tier: Int,
    val ranking: LazarusRanking,
    /**
     * True when an empty item set is a defined state with its own meaning
     * (e.g. kind 10044 announces "no longer using NIP-4e") rather than the
     * fingerprint of a clobbering client. Empty candidates on these kinds
     * are valid options, never labeled as damage, and ranking is disabled:
     * the user must choose with intent.
     */
    val meaningfulEmpty: Boolean,
    val requiredWarnings: List<LazarusWarning>,
    val itemCount: (tags: List<List<String>>, content: String) -> LazarusItemCount,
    /** Tag types counted among decrypted private items (NIP-51). */
    val privateItemTypes: List<String>? = null
)

private val MUTE_TAG_TYPES = listOf("p", "word", "t", "e")

private fun countTags(types: List<String>, mayHavePrivateItems: Boolean = false):
    (List<List<String>>, String) -> LazarusItemCount = { tags, content ->
    val count = tags.count { it.firstOrNull() != null && it[0] in types }
    // Only encrypted content holds private items; kind 3 content is often
    // legacy relay JSON, which isn't a hidden part of the list
    if (!mayHavePrivateItems || getContentEncryption(content) == null) {
        LazarusItemCount(count = count, partial = false)
    } else {
        val estimate = estimatePrivateItems(content)
        LazarusItemCount(
            count = count,
            partial = true,
            privateEstimate = estimate?.let { LazarusCountRange(it.min, it.max) }
        )
    }
}

private fun contentPresence(tags: List<List<String>>, content: String): LazarusItemCount =
    LazarusItemCount(count = if (content.trim().isNotEmpty()) 1 else 0, partial = false)

val LAZARUS_REGISTRY: Map<Int, LazarusKindProfile> = mapOf(
    3 to LazarusKindProfile(
        kind = 3,
        name = "Follow list",
        tier = 1,
        ranking = LazarusRanking.COUNT,
        meaningfulEmpty = false,
        requiredWarnings = emptyList(),
        itemCount = countTags(listOf("p"), mayHavePrivateItems = true),
        privateItemTypes = listOf("p")
    ),
    10000 to LazarusKindProfile(
        kind = 10000,
        name = "Mute list",
        tier = 1,
        ranking = LazarusRanking.COUNT,
        meaningfulEmpty = false,
        requiredWarnings = listOf(LazarusWarning.REMUTE, LazarusWarning.AFFECTS_OTHERS),
        itemCount = countTags(MUTE_TAG_TYPES, mayHavePrivateItems = true),
        privateItemTypes = MUTE_TAG_TYPES
    ),
    0 to LazarusKindProfile(
        kind = 0,
        name = "Profile metadata",
        tier = 2,
        ranking = LazarusRanking.RECENCY,
        meaningfulEmpty = false,
        requiredWarnings = emptyList(),
        itemCount = ::contentPresence
    ),
    10003 to LazarusKindProfile(
        kind = 10003,
        name = "Bookmarks",
        tier = 2,
        ranking = LazarusRanking.COUNT,
        meaningfulEmpty = false,
        requiredWarnings = emptyList(),
        itemCount = countTags(listOf("e", "a"), mayHavePrivateItems = true),
        privateItemTypes = listOf("e", "a")
    ),
    10044 to LazarusKindProfile(
        kind = 10044,
        name = "Encryption key list (NIP-4e)",
        tier = 2,
        ranking = LazarusRanking.INTENT,
        meaningfulEmpty = true,
        requiredWarnings = listOf(LazarusWarning.AFFECTS_OTHERS),
        itemCount = countTags(listOf("p"))
    ),
    10002 to LazarusKindProfile(
        kind = 10002,
        name = "Relay list",
        tier = 3,
        ranking = LazarusRanking.RECENCY,
        meaningfulEmpty = false,
        requiredWarnings = listOf(LazarusWarning.STALE_RELAYS),
        itemCount = countTags(listOf("r"))
    ),
    10050 to LazarusKindProfile(
        kind = 10050,
        name = "DM relay inbox",
        tier = 3,
        ranking = LazarusRanking.RECENCY,
        meaningfulEmpty = false,
        requiredWarnings = listOf(LazarusWarning.STALE_RELAYS),
        itemCount = countTags(listOf("relay"))
    ),
    10006 to LazarusKindProfile(
        kind = 10006,
        name = "Blocked relays",
        tier = 3,
        ranking = LazarusRanking.COUNT,
        meaningfulEmpty = false,
        requiredWarnings = emptyList(),
        itemCount = countTags(listOf("relay"))
    )
)

/** Registry order: tier ascending, then kind ascending. */
fun getLazarusKindProfiles(): List<LazarusKindProfile> =
    LAZARUS_REGISTRY.values.sortedWith(compareBy({ it.tier }, { it.kind }))

fun getLazarusKindProfile(kind: Int): LazarusKindProfile? = LAZARUS_REGISTRY[kind]
