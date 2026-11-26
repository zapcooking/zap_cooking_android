package cooking.zap.app.repo

import org.junit.Assert.assertEquals
import java.util.Locale
import org.junit.Test

/**
 * Spark wallets hold tokens alongside sats, and `Payment.amount` is
 * documented as "satoshis **or** token base units". Reading a token
 * payment's base units as sats turned a $15.77 USDB transfer into
 * "+15,766,673 sats".
 *
 * Mirrors the iOS `TokenPaymentDisplayTests` case for case — the two apps
 * render the same wallet and must not disagree about a row.
 */
class TokenAmountTest {

    // --- Decimal shifting ---

    @Test
    fun `scales by the token's decimals`() {
        // USDB-shaped: 6 decimals. 15,766,673 base units is 15.766673 USDB,
        // not 15.7M sats.
        assertEquals("15.766673", TokenAmount.scale("15766673", 6))
    }

    @Test
    fun `trailing zeros are dropped`() {
        assertEquals("150", TokenAmount.scale("150000000", 6))
        assertEquals("1.5", TokenAmount.scale("1500000", 6))
    }

    @Test
    fun `amounts under one unit keep their leading zero`() {
        assertEquals("0.5", TokenAmount.scale("500000", 6))
        assertEquals("0.000001", TokenAmount.scale("1", 6))
    }

    @Test
    fun `zero decimals passes through`() {
        assertEquals("42", TokenAmount.scale("42", 0))
    }

    @Test
    fun `zero is zero`() {
        assertEquals("0", TokenAmount.scale("0", 6))
    }

    /**
     * Base units are `U128`. Anything routed through `Long` would overflow
     * and silently read as 0, so the scaler works on the decimal string.
     */
    @Test
    fun `amounts beyond Long survive`() {
        val huge = "340282366920938463463374607431768211455" // U128.max
        assertEquals(
            "3402823669209384634633746074317682114.55",
            TokenAmount.scale(huge, 2)
        )
    }

    @Test
    fun `non-numeric input is returned unchanged`() {
        assertEquals("abc", TokenAmount.scale("abc", 6))
        assertEquals("", TokenAmount.scale("", 6))
    }

    // --- Row-sized display ---

    /**
     * USDB is dollars. Six decimal places is both unreadable at a glance and
     * wrong for what the number means.
     */
    @Test
    fun `compact form rounds to two places`() {
        assertEquals("15.77", TokenAmount.compact("15.766673", locale = Locale.US))
        assertEquals("15.80", TokenAmount.compact("15.802229", locale = Locale.US))
    }

    @Test
    fun `whole amounts pass through without padding`() {
        // "150" has no decimal point, so a whole token count doesn't get
        // dressed up to look like money.
        assertEquals("150", TokenAmount.compact("150", locale = Locale.US))
        assertEquals("150.00", TokenAmount.compact("150.0", locale = Locale.US))
    }

    /** Dust must never render as "0.00" — that reads as nothing arriving. */
    @Test
    fun `dust keeps full precision`() {
        assertEquals("0.000001", TokenAmount.compact("0.000001", locale = Locale.US))
    }

    @Test
    fun `real zero is allowed to be zero`() {
        assertEquals("0.00", TokenAmount.compact("0.0", locale = Locale.US))
    }

    @Test
    fun `compact form groups thousands`() {
        assertEquals("1,234.50", TokenAmount.compact("1234.5", locale = Locale.US))
    }

    @Test
    fun `compact form leaves unparseable input alone`() {
        assertEquals("1.2.3", TokenAmount.compact("1.2.3", locale = Locale.US))
    }

    /** The row shows the compact form; details keep every digit. */
    @Test
    fun `full precision survives alongside the compact form`() {
        val scaled = TokenAmount.scale("15766673", 6)
        assertEquals("15.766673", scaled)
        assertEquals("15.77", TokenAmount.compact(scaled, locale = Locale.US))
    }
}
