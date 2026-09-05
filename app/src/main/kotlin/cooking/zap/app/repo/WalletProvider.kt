package cooking.zap.app.repo

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the account has a usable in-app wallet, per Note Review
 * finding 0.6 — the exact analog of web's wallet kinds 3 (NWC) /
 * 4 (Spark) routing. The [mode] check is MANDATORY: FeedViewModel's
 * `activeWalletProvider` maps [WalletMode.NONE] to the NWC repo too, so
 * a stale saved `nwc_uri` could otherwise read as "has wallet".
 * [WalletProvider.hasConnection] is configured-ness (saved URI /
 * mnemonic), deliberately not the live `isConnected` socket state — a
 * configured-but-idle wallet still routes in-app; `payInvoice` connects
 * on demand.
 */
fun hasInAppWallet(mode: WalletMode, provider: WalletProvider): Boolean =
    mode != WalletMode.NONE && provider.hasConnection()

interface WalletProvider {
    val balance: StateFlow<Long?>
    val isConnected: StateFlow<Boolean>
    val statusLog: SharedFlow<String>

    /** Emits the amount in msats whenever an incoming payment is received. */
    val paymentReceived: SharedFlow<Long>

    /**
     * Emits whenever the transaction set may have changed (e.g. a payment
     * went pending or settled, a deposit was claimed) so the UI can reload.
     */
    val transactionsChanged: SharedFlow<Unit>

    fun hasConnection(): Boolean
    fun connect()
    fun disconnect()
    suspend fun fetchBalance(): Result<Long>
    suspend fun payInvoice(bolt11: String): Result<String>
    suspend fun makeInvoice(amountMsats: Long, description: String, expirySecs: Int = 3600): Result<String>
    suspend fun listTransactions(limit: Int = 50, offset: Int = 0): Result<List<WalletTransaction>>
}

data class WalletTransaction(
    val type: String,
    val description: String?,
    val paymentHash: String,
    val amountMsats: Long,
    val feeMsats: Long = 0,
    val createdAt: Long,
    val settledAt: Long?,
    /** Pubkey of the counterparty (recipient for outgoing, sender for incoming zaps). */
    val counterpartyPubkey: String? = null,
    /** True while the payment is unconfirmed/unsettled (e.g. an in-flight Lightning payment). */
    val pending: Boolean = false,
    /** True for on-chain Bitcoin deposits/withdrawals (vs Lightning). */
    val isOnchain: Boolean = false,
    /**
     * Ticker of the asset this row moved, when it wasn't bitcoin - e.g. "USDB".
     * Null for every sats payment, which is the overwhelming majority.
     *
     * Spark wallets can hold tokens alongside sats, and Payment.amount is
     * documented as "satoshis OR token base units". This app offers no token
     * conversion, but a wallet restored from the same seed in an app that does
     * hands us those payments anyway, and reading their base units as sats
     * turns a 15.77 USDB transfer into "15,766,673 sats".
     *
     * When this is set, amountMsats / feeMsats are zero: there is no honest
     * sats value for a token transfer, so nothing sats-denominated - including
     * fiat conversion - may be derived from this row.
     */
    val assetTicker: String? = null,
    /**
     * Amount already scaled by the token's decimals, at full precision. Kept as
     * a String because token base units are u128 and overflow Long.
     */
    val assetAmount: String? = null,
    /** Fee in the same asset, scaled the same way. Null when the fee is zero. */
    val assetFee: String? = null
) {
    /** True when this row moved something other than bitcoin. */
    val isTokenTransfer: Boolean get() = assetTicker != null

    /** Row-sized amount: two decimal places, full precision kept in [assetAmount]. */
    val assetAmountCompact: String? get() = assetAmount?.let { TokenAmounts.compact(it) }
}

/**
 * Formatting for non-bitcoin assets that reach the wallet from another app
 * sharing the same seed. Pure string / BigDecimal math so it is unit testable
 * without the SDK.
 */
object TokenAmounts {
    /**
     * Shift a token's base-unit amount by its decimal places.
     *
     * Operates on the decimal string rather than a numeric type on purpose:
     * base units are u128 and overflow Long, and Double loses cents well
     * before that. Trailing zeros are dropped so a whole amount reads "150"
     * rather than "150.000000".
     */
    fun scale(baseUnits: String, decimals: Int): String {
        val digits = baseUnits.trim()
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return baseUnits
        if (decimals <= 0) return digits

        val padded = if (digits.length > decimals) digits
                     else "0".repeat(decimals - digits.length + 1) + digits
        val whole = padded.substring(0, padded.length - decimals)
        val fraction = padded.substring(padded.length - decimals).trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    /**
     * Round a scaled amount to two places for the transaction row. USDB is
     * dollars, and six places is both unreadable at a glance and wrong for
     * what the number means; the expanded detail keeps full precision.
     *
     * Dust that would round away to "0.00" keeps its full precision instead -
     * that would otherwise read as nothing having arrived.
     */
    fun compact(scaled: String, places: Int = 2): String {
        if (!scaled.contains('.')) return scaled
        val value = scaled.toBigDecimalOrNull() ?: return scaled
        val rounded = value.setScale(places, java.math.RoundingMode.HALF_UP)
        if (rounded.signum() == 0 && value.signum() != 0) return scaled
        return java.text.DecimalFormat("#,##0.00").format(rounded)
    }
}
