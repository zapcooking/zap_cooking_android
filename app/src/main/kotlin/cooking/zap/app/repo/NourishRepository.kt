package cooking.zap.app.repo

import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NourishParser
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.relay.AuthedRelayReader
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a recipe's Nourish health score (kind 30078) from the Pantry relay
 * (concern 2.4a). Pantry requires **NIP-42 AUTH** on every read, so this needs
 * a signing key (READ_ONLY can't auth → returns null without trying — quiet
 * absence).
 *
 * The auth handshake + query + the reconnect/resync race are handled by the
 * reusable [AuthedRelayReader] (which Nourish compute / future member reads
 * reuse). Results are cached per recipe key.
 */
class NourishRepository(relayPool: RelayPool) {

    private val reader = AuthedRelayReader(relayPool)
    private val cache = ConcurrentHashMap<String, NourishScore>()

    /**
     * @param hasSigningKey false for READ_ONLY accounts — they can't NIP-42
     *   auth to Pantry, so we don't even try.
     */
    suspend fun fetchScore(
        recipeAuthor: String,
        recipeDTag: String,
        hasSigningKey: Boolean,
    ): NourishScore? {
        if (!hasSigningKey || recipeAuthor.isBlank() || recipeDTag.isBlank()) return null
        val key = "$recipeAuthor:$recipeDTag"
        cache[key]?.let { return it }

        val filter = Filter(
            kinds = listOf(NourishParser.KIND),
            authors = listOf(NourishParser.SERVICE_PUBKEY),
            dTags = listOf(NourishParser.dTag(recipeAuthor, recipeDTag)),
            limit = 1,
        )
        val event = reader.read(RelayConfig.MEMBERS_RELAY, filter)
            .firstOrNull { it.kind == NourishParser.KIND } ?: return null
        val parsed = NourishParser.parse(event.content) ?: return null
        cache[key] = parsed
        return parsed
    }

    fun clear() = cache.clear()
}
