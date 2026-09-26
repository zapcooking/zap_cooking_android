package cooking.zap.app.lazarus

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * NIP-51 private items: a list can carry items encrypted to its author's own
 * key in `content` (NIP-44, or NIP-04 in older events) next to its public
 * tags. A private-only mute list has no public tags at all, so counting tags
 * alone reads a full list and an emptied one as the same zero. These helpers
 * size private items from the encrypted payload without decrypting, and
 * count them exactly once decrypted.
 *
 * Ported from the Lazarus spec's reference implementation (dmnyc/jumble-spark,
 * feat/lazarus-data-recovery; vendored by zapcooking/frontend#753), spec
 * 0.5.0-draft. Kotlin port of private-items.ts; the conformance vectors are
 * real NIP-44/NIP-04 ciphertexts pinned in LazarusCoreTest.
 */
enum class LazarusEncryption { NIP04, NIP44 }

/** Inclusive plaintext-length range [min, max] in bytes. */
data class LazarusLengthRange(val min: Int, val max: Int)

private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

// NIP-44 v2 payload: version (1) + nonce (32) + [u16 length (2) + padded plaintext] + mac (32)
private const val NIP44_OVERHEAD_BYTES = 67
// The smallest payload holds 32 bytes of padded plaintext: 99 bytes, 132 base64 characters
private const val NIP44_MIN_PAYLOAD_CHARS = 132

/**
 * A private item is a JSON-encoded tag, most often ["p", <64-hex pubkey>]:
 * 72 characters, plus a comma between items. Estimates assume that shape,
 * so lists heavy on short words or hashtags hold more items than estimated.
 */
private const val BYTES_PER_ITEM = 73

/** How a list's content is encrypted, or null when it isn't (e.g. kind 3 relay JSON). */
fun getContentEncryption(content: String): LazarusEncryption? {
    val value = content.trim()
    if (value.isEmpty()) return null
    val parts = value.split("?iv=")
    if (parts.size >= 2) {
        val cipherText = parts[0]
        val iv = parts[1]
        val rest = parts.drop(2)
        return if (rest.isEmpty() && BASE64.matches(cipherText) && BASE64.matches(iv)) {
            LazarusEncryption.NIP04
        } else null
    }
    return if (value.length >= NIP44_MIN_PAYLOAD_CHARS && BASE64.matches(value)) {
        LazarusEncryption.NIP44
    } else null
}

private fun base64ByteLength(value: String): Int? {
    if (value.length % 4 != 0) return null
    val padding = when {
        value.endsWith("==") -> 2
        value.endsWith("=") -> 1
        else -> 0
    }
    return (value.length / 4) * 3 - padding
}

/** NIP-44 v2 padded length for a plaintext of [length] bytes. */
private fun nip44PaddedLength(length: Int): Int {
    if (length <= 32) return 32
    // Smallest power of two strictly greater than (length - 1)
    val exp = 31 - java.lang.Integer.numberOfLeadingZeros(length - 1) + 1
    val nextPower = if (1 shl exp < 0) Int.MAX_VALUE else 1 shl exp
    val chunk = if (nextPower <= 256) 32 else nextPower / 8
    return chunk * ((length - 1) / chunk + 1)
}

/** The range of plaintext lengths (bytes) an encrypted payload can hold, from its size alone. */
fun getPlaintextLengthRange(content: String): LazarusLengthRange? {
    val value = content.trim()
    return when (getContentEncryption(value)) {
        LazarusEncryption.NIP04 -> {
            val bytes = base64ByteLength(value.split("?iv=")[0])
            // AES-CBC with PKCS#7 always adds 1–16 bytes of padding
            if (bytes == null || bytes % 16 != 0) null
            else LazarusLengthRange(min = bytes - 16, max = bytes - 1)
        }
        LazarusEncryption.NIP44 -> {
            val bytes = base64ByteLength(value) ?: return null
            val padded = bytes - NIP44_OVERHEAD_BYTES
            if (padded < 32 || padded > 65536 || nip44PaddedLength(padded) != padded) return null
            // The smallest plaintext that pads to this length (binary search)
            var low = 1
            var high = padded
            while (low < high) {
                val mid = (low + high) / 2
                if (nip44PaddedLength(mid) >= padded) high = mid else low = mid + 1
            }
            LazarusLengthRange(min = low, max = padded)
        }
        null -> null
    }
}

/** How many private items an encrypted payload holds, estimated from its size. */
fun estimatePrivateItems(content: String): LazarusLengthRange? {
    val range = getPlaintextLengthRange(content) ?: return null
    // A JSON array of n such tags is 73n + 1 bytes
    return LazarusLengthRange(
        min = max(floor((range.min - 1).toDouble() / BYTES_PER_ITEM).toInt(), 0),
        max = max(ceil((range.max - 1).toDouble() / BYTES_PER_ITEM).toInt(), 0)
    )
}

/** A parsed plaintext is a private-tag list only when it is an array of
 *  string arrays — anything else (a kind 3's relay JSON, a profile blob) is
 *  not private items. */
fun parsePrivateTags(plainText: String): List<List<String>>? = try {
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(plainText)
    val array = parsed as? kotlinx.serialization.json.JsonArray ?: return null
    array.map { element ->
        val tag = element as? kotlinx.serialization.json.JsonArray ?: return null
        tag.map { item -> (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
    }
} catch (_: Exception) {
    null
}

fun countItemTags(tags: List<List<String>>, types: Collection<String>): Int =
    tags.count { it.firstOrNull() != null && it[0] in types }
