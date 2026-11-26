package cooking.zap.app.repo

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * Display helpers for Spark's non-bitcoin assets — in practice USDB, the
 * stablecoin behind the wallet's opt-in USD balance.
 *
 * Spark reports every payment amount through the same `Payment.amount`
 * field, documented as "satoshis **or token base units**". Reading a token
 * payment's base units as satoshis turns a $15.77 USDB transfer into
 * "+15,766,673 sats" — off by eight orders of magnitude, in the direction
 * that looks like a windfall. Everything here exists to keep those two
 * denominations apart.
 *
 * Amounts are handled as decimal strings rather than a numeric type on
 * purpose: token base units are `U128` and can exceed `Long`, and `Double`
 * starts misrepresenting cents well before that. These are money-shaped
 * values, so rounding goes through [BigDecimal].
 *
 * Kept byte-for-byte equivalent to the iOS `WalletTransaction`
 * scale/compact helpers so the two apps never disagree about the same row.
 */
object TokenAmount {

    /**
     * Shift a token's base-unit amount by its decimal places for display.
     *
     * Trailing zeros are dropped so a whole amount reads "150" rather than
     * "150.000000". Anything that isn't a run of digits is passed through
     * untouched rather than turned into a number it isn't.
     */
    fun scale(baseUnits: String, decimals: Int): String {
        val digits = baseUnits.trim()
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return baseUnits
        if (decimals <= 0) return digits

        // Amounts smaller than one whole unit need a leading zero, not ".5".
        val padded = if (digits.length > decimals) {
            digits
        } else {
            "0".repeat(decimals - digits.length + 1) + digits
        }
        val split = padded.length - decimals
        val whole = padded.substring(0, split)
        val fraction = padded.substring(split).trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    /**
     * Round a scaled token amount to [places] decimal places for a list row.
     *
     * USDB is dollars, and six decimal places is both unreadable at a glance
     * and wrong for what the number means. Dust that would round away to
     * "0.00" keeps its full precision instead — a real balance must never
     * display as nothing having arrived. Detail views show [scale]'s output
     * unrounded.
     */
    fun compact(
        scaled: String,
        places: Int = 2,
        locale: Locale = Locale.getDefault()
    ): String {
        if (!scaled.contains('.')) return scaled
        val value = try {
            BigDecimal(scaled)
        } catch (_: NumberFormatException) {
            return scaled
        }
        val rounded = value.setScale(places, RoundingMode.HALF_UP)
        if (rounded.signum() == 0 && value.signum() != 0) return scaled

        val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
        format.minimumFractionDigits = places
        format.maximumFractionDigits = places
        format.isGroupingUsed = true
        return format.format(rounded)
    }
}
