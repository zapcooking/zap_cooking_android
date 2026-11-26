package cooking.zap.app.fresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Port of iOS `FreshFinishLineTests` (web `finishLine.test.ts`, free tier). */
class FreshFinishLineTest {
    @Test
    fun nothingUntilTheEndOfThe14DayWindow() {
        val f = FreshFinishLine.of(reached = false)
        assertFalse(f.caughtUp)
        assertFalse(f.recipeRow)
        assertEquals(FreshFinishLine.NONE, f)
    }

    @Test
    fun freeTier_caughtUpCardThenRecipeRow_noFloorCardNoOlderPosts() {
        assertEquals(FreshFinishLine(caughtUp = true, recipeRow = true), FreshFinishLine.of(reached = true))
    }
}
