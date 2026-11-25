package cooking.zap.app.fresh

/**
 * The end of the 14-day window, ported from iOS `FreshFinishLine.swift` (web
 * `src/lib/freshFeed/finishLine.ts`) for the free tier only.
 *
 * Web shows "You're all caught up", then an opt-in "Keep exploring", then for
 * members "Older posts" and for everyone else a membership card. Android v1 is
 * free tier only (build spec §1: no login to the feed relay), so the caught-up
 * card is followed by a recipe row and there is no floor card, no "Older
 * posts" and no membership wait.
 */
data class FreshFinishLine(
    /** The caught-up card. */
    val caughtUp: Boolean,
    /** The recipe row after it. */
    val recipeRow: Boolean,
) {
    companion object {
        val NONE = FreshFinishLine(caughtUp = false, recipeRow = false)

        /**
         * [reached]: the feed reached the end of the free window (a short
         * page, or the relay's `auth-required` floor close).
         */
        fun of(reached: Boolean): FreshFinishLine =
            if (reached) FreshFinishLine(caughtUp = true, recipeRow = true) else NONE
    }
}
