package cooking.zap.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the web client's `sparkPayment.test.ts`. Same wallet, same rows —
 * if these two suites drift, the two apps start telling the user different
 * things about the same money.
 */
class SparkPaymentAssetTest {

    private fun usdbSide(amount: String = "15866330") = ConversionAssetSide(
        assetIdentifier = "usdb-token",
        ticker = "USDB",
        decimals = 6,
        baseUnits = amount
    )

    private fun bitcoinSide(amount: String = "23914") = ConversionAssetSide(
        assetIdentifier = null,
        ticker = "BTC",
        decimals = 0,
        baseUnits = amount
    )

    // --- Asset detection ---

    @Test
    fun `uses conversion details for a stable-balance Lightning receive`() {
        val asset = extractPaymentAsset(
            amountBaseUnits = "23914",
            isTokenMethod = false,
            tokenTicker = null,
            tokenDecimals = null,
            conversions = listOf(SparkConversion(from = bitcoinSide(), to = usdbSide()))
        )
        assertEquals(PaymentAsset("USDB", "15866330", 6, feeInAsset = false), asset)
    }

    /**
     * The token leg's own amount is what the user ended up with.
     * `Payment.amount` on this row is the sats that came in over Lightning,
     * and reading it as USDB would understate the balance by a factor of
     * ~660.
     */
    @Test
    fun `takes the token leg's amount, not the payment's`() {
        val asset = extractPaymentAsset(
            amountBaseUnits = "23914",
            isTokenMethod = false,
            tokenTicker = null,
            tokenDecimals = null,
            conversions = listOf(SparkConversion(from = bitcoinSide(), to = usdbSide()))
        )
        assertEquals("15866330", asset?.baseUnits)
        assertEquals("15.86633", TokenAmount.scale(asset!!.baseUnits, asset.decimals))
    }

    @Test
    fun `does not re-denominate ordinary Lightning payments`() {
        assertNull(
            extractPaymentAsset(
                amountBaseUnits = "21",
                isTokenMethod = false,
                tokenTicker = null,
                tokenDecimals = null
            )
        )
    }

    /** A direct token transfer takes its ticker and decimals from metadata. */
    @Test
    fun `token details win over everything else`() {
        val asset = extractPaymentAsset(
            amountBaseUnits = "15766673",
            isTokenMethod = true,
            tokenTicker = "USDB",
            tokenDecimals = 6
        )
        assertEquals(PaymentAsset("USDB", "15766673", 6, feeInAsset = true), asset)
    }

    /**
     * The SDK notes payment details can be empty. `PaymentMethod.TOKEN` alone
     * still means "not sats", so the base units show under a neutral label
     * rather than being passed off as satoshis.
     */
    @Test
    fun `token method alone falls back to a neutral label`() {
        val asset = extractPaymentAsset(
            amountBaseUnits = "15766673",
            isTokenMethod = true,
            tokenTicker = null,
            tokenDecimals = null
        )
        assertEquals(PaymentAsset("tokens", "15766673", 0, feeInAsset = true), asset)
    }

    @Test
    fun `a blank ticker does not produce a nameless asset`() {
        val asset = extractPaymentAsset(
            amountBaseUnits = "5",
            isTokenMethod = false,
            tokenTicker = "  ",
            tokenDecimals = 2
        )
        assertEquals("Token", asset?.ticker)
    }

    /** The bitcoin leg has no identifier, so it can never be read as the asset. */
    @Test
    fun `the bitcoin leg is never mistaken for the asset`() {
        val asset = extractPaymentAsset(
            amountBaseUnits = "23914",
            isTokenMethod = false,
            tokenTicker = null,
            tokenDecimals = null,
            conversions = listOf(SparkConversion(from = bitcoinSide(), to = bitcoinSide()))
        )
        assertNull(asset)
    }

    // --- Satoshi value ---

    @Test
    fun `an ordinary payment's amount is already sats`() {
        assertEquals(21L, extractPaymentSats("21", hasAsset = false))
    }

    @Test
    fun `a conversion's sats come from the bitcoin leg`() {
        assertEquals(
            23_914L,
            extractPaymentSats(
                amountBaseUnits = "15866330",
                hasAsset = true,
                conversions = listOf(SparkConversion(from = bitcoinSide(), to = usdbSide()))
            )
        )
    }

    /**
     * A pure token transfer has no bitcoin leg, so it has no honest sats
     * value — and must never report its base units as one.
     */
    @Test
    fun `token base units never become sats`() {
        assertEquals(0L, extractPaymentSats("15866330", hasAsset = true))
    }

    /** `U128` amounts exceed `Long`; the fallback is zero, not an overflow. */
    @Test
    fun `an amount beyond Long does not wrap`() {
        val huge = "340282366920938463463374607431768211455"
        assertEquals(0L, extractPaymentSats(huge, hasAsset = false))
    }

    // --- Conversion labelling ---

    @Test
    fun `identifies a conversion from Bitcoin`() {
        assertEquals(
            "Bitcoin",
            extractConversionFrom(listOf(SparkConversion(from = bitcoinSide(), to = usdbSide())))
        )
    }

    @Test
    fun `identifies a conversion from USDB`() {
        assertEquals(
            "USDB",
            extractConversionFrom(
                listOf(SparkConversion(from = usdbSide("15800000"), to = bitcoinSide("20339")))
            )
        )
    }

    @Test
    fun `a payment with no conversions is not labelled one`() {
        assertNull(extractConversionFrom(emptyList()))
    }

    // --- The row the user sees ---

    @Test
    fun `a conversion row is signless and denominated in the token`() {
        val conversions = listOf(SparkConversion(from = bitcoinSide(), to = usdbSide()))
        val asset = extractPaymentAsset(
            amountBaseUnits = "23914",
            isTokenMethod = false,
            tokenTicker = null,
            tokenDecimals = null,
            conversions = conversions
        )!!
        val tx = WalletTransaction(
            type = "incoming",
            description = null,
            paymentHash = "h",
            amountMsats = extractPaymentSats("23914", true, conversions) * 1000,
            feeMsats = 0,
            createdAt = 0,
            settledAt = null,
            assetTicker = asset.ticker,
            assetAmount = TokenAmount.scale(asset.baseUnits, asset.decimals),
            conversionFrom = extractConversionFrom(conversions)
        )

        assertTrue(tx.isConversion)
        assertTrue(tx.isTokenTransfer)
        assertEquals("Bitcoin", tx.conversionFrom)
        assertEquals("15.86633", tx.assetAmount)
        // The sats leg is real and stays sats-denominated; it is simply not
        // what this row displays.
        assertEquals(23_914_000L, tx.amountMsats)
    }

    @Test
    fun `a sats row is neither a conversion nor a token transfer`() {
        val tx = WalletTransaction(
            type = "incoming",
            description = null,
            paymentHash = "h",
            amountMsats = 21_000,
            feeMsats = 0,
            createdAt = 0,
            settledAt = null
        )
        assertFalse(tx.isConversion)
        assertFalse(tx.isTokenTransfer)
    }
}
