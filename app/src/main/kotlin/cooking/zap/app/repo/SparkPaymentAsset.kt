package cooking.zap.app.repo

/**
 * Reads the asset a Spark payment actually moved out of the shape the SDK
 * hands us. Port of the web client's `sparkPayment.ts`, kept deliberately
 * parallel so the same wallet renders the same history in both apps.
 *
 * The problem these functions exist to solve: Spark reports every amount
 * through one `Payment.amount` field, documented as "satoshis **or** token
 * base units", and a stable-balance receive keeps its original Lightning
 * method while its real amount is recorded only on the token side of the
 * conversion. Reading that field as sats turns a $15.77 USDB conversion into
 * "+15,866,330 sats".
 */

/**
 * A non-bitcoin asset moved by a Spark payment — in practice USDB.
 *
 * When a payment carries one of these, the row is rendered in the asset's own
 * denomination and no fiat rate is applied: the rate converts satoshis, so
 * applying it here would only produce a second wrong number.
 */
data class PaymentAsset(
    /** e.g. "USDB". */
    val ticker: String,
    /** Unscaled base units as a decimal string; `U128`, so never a `Long`. */
    val baseUnits: String,
    val decimals: Int,
    /**
     * Whether `Payment.fees` is denominated in this asset too.
     *
     * True for a genuine token transfer, where the SDK reports the fee in
     * token base units. False when the asset came from conversion details:
     * that payment settled over Lightning, so its fee is in sats while its
     * amount is in token base units. There is no single denomination that
     * describes both, so the fee is dropped rather than labelled with a
     * currency it isn't in.
     */
    val feeInAsset: Boolean
)

/**
 * One side of a Spark conversion, flattened to the fields we read.
 *
 * Only the token side carries an [assetIdentifier] — that is what tells the
 * two legs of a conversion apart.
 */
data class ConversionAssetSide(
    val assetIdentifier: String?,
    val ticker: String?,
    val decimals: Int,
    val baseUnits: String
)

/** A conversion, as the pair of sides we read off `ConversionDetails`. */
data class SparkConversion(
    val from: ConversionAssetSide,
    val to: ConversionAssetSide
)

/** Every side of every conversion on a payment, in order. */
fun conversionSides(conversions: List<SparkConversion>): List<ConversionAssetSide> =
    conversions.flatMap { listOf(it.from, it.to) }

/**
 * True when the conversion's destination is bitcoin — the payment ended as
 * sats, so the row is sats-denominated and the token side of the conversion
 * is what was *spent*, not what arrived.
 *
 * Denominating both legs in the token — the web client's current rule —
 * makes a USD-mode round-trip read as two identical "1.20 USDB" rows with
 * the sats leg's outcome invisible.
 */
fun conversionDestinedForBitcoin(conversions: List<SparkConversion>): Boolean =
    conversions.isNotEmpty() && conversions.all {
        it.to.ticker.equals("BTC", ignoreCase = true) ||
            it.to.ticker.equals("Bitcoin", ignoreCase = true)
    }

/**
 * Work out which asset a payment moved, if not bitcoin. Returns null for an
 * ordinary sats payment, which is the overwhelming majority.
 *
 * Three sources, in order of trustworthiness:
 *
 *  1. `PaymentDetails.Token` metadata — a direct token transfer. Ticker and
 *     decimals come straight from the token's own definition, and the amount
 *     is the payment's own.
 *  2. The token side of the conversion details. Its amount is used, **not**
 *     the payment's: on a stable-balance Lightning receive `Payment.amount`
 *     is the sats that came in, while the USDB the user actually ended up
 *     with is recorded only on this side.
 *  3. `PaymentMethod.TOKEN` alone — the SDK notes the details can be empty.
 *     Without metadata there are no decimals to scale by, so the base units
 *     show under a neutral label rather than being passed off as sats.
 */
fun extractPaymentAsset(
    amountBaseUnits: String,
    isTokenMethod: Boolean,
    tokenTicker: String?,
    tokenDecimals: Int?,
    conversions: List<SparkConversion> = emptyList()
): PaymentAsset? {
    if (tokenDecimals != null) {
        return PaymentAsset(
            ticker = tokenTicker?.takeIf { it.isNotBlank() } ?: "Token",
            baseUnits = amountBaseUnits,
            decimals = tokenDecimals,
            feeInAsset = true
        )
    }

    val tokenSide = conversionSides(conversions).firstOrNull {
        !it.assetIdentifier.isNullOrBlank()
    }
    if (tokenSide != null) {
        return PaymentAsset(
            ticker = tokenSide.ticker?.takeIf { it.isNotBlank() } ?: "Token",
            baseUnits = tokenSide.baseUnits,
            decimals = tokenSide.decimals,
            feeInAsset = false
        )
    }

    if (isTokenMethod) {
        return PaymentAsset(
            ticker = "tokens",
            baseUnits = amountBaseUnits,
            decimals = 0,
            feeInAsset = true
        )
    }

    return null
}

/**
 * The payment's satoshi value, which must stay strictly sats whatever else
 * the row displays.
 *
 * With no asset, `Payment.amount` already is sats. With one, that field may
 * hold token base units, so the figure is taken from the bitcoin leg of the
 * conversion instead — and is zero for a pure token transfer, which has no
 * bitcoin leg and therefore no honest sats value at all.
 */
fun extractPaymentSats(
    amountBaseUnits: String,
    hasAsset: Boolean,
    conversions: List<SparkConversion> = emptyList()
): Long {
    if (!hasAsset) return amountBaseUnits.toLongOrNull() ?: 0L
    val bitcoinSide = conversionSides(conversions).firstOrNull {
        it.ticker == "BTC" || it.ticker == "Bitcoin"
    }
    return bitcoinSide?.baseUnits?.toLongOrNull() ?: 0L
}

/**
 * Name of the asset a conversion started from — "Bitcoin" or "USDB" — for
 * rows that read "Converted from Bitcoin" rather than "Received".
 *
 * A conversion is neither a credit nor a debit: the same money stays in the
 * wallet in a different denomination. Labelling one "Received" invites the
 * reading that new funds arrived, and shows it twice over when both legs of
 * one swap land in the history.
 *
 * Returns null when the payment isn't a conversion.
 */
fun extractConversionFrom(conversions: List<SparkConversion>): String? {
    val ticker = conversions.firstNotNullOfOrNull { it.from.ticker?.takeIf { t -> t.isNotBlank() } }
        ?: return null
    return if (ticker == "BTC") "Bitcoin" else ticker
}
