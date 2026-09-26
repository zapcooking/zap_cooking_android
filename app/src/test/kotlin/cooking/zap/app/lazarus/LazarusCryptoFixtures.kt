package cooking.zap.app.lazarus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Real NIP-44/NIP-04 ciphertexts for the private-item sizing conformance
 * vectors, generated with nostr-tools using the reference implementation's
 * own test recipe: NIP-44 encryptions (to self) of a JSON array of n
 * ["p", <64-hex>] tags, plus one NIP-04 encryption of the 40-item list.
 * Kept as a test resource so the compiler never parses megabyte literals.
 */
object LazarusCryptoFixtures {

    @Serializable
    data class Fixtures(
        val n: Int,
        @SerialName("plain") val plainText: String,
        @SerialName("nip44") val nip44Content: String
    )

    @Serializable
    data class Bundle(
        val conversation: Conversation,
        val fixtures: List<Fixtures>,
        val nip04: Nip04
    ) {
        @Serializable
        data class Conversation(val sk: String, val pk: String)

        @Serializable
        data class Nip04(val plain: String, @SerialName("content") val nip04Content: String)
    }

    private val bundle: Bundle by lazy {
        Json.decodeFromString(
            Bundle.serializer(),
            LazarusCryptoFixtures::class.java.getResourceAsStream("/lazarus-crypto-fixtures.json")!!
                .readBytes().decodeToString()
        )
    }

    val NIP44_CASES: List<Fixtures> get() = bundle.fixtures
    val NIP04_PLAIN: String get() = bundle.nip04.plain
    val NIP04_CONTENT: String get() = bundle.nip04.nip04Content
}
