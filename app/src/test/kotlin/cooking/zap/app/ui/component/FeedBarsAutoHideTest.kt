package cooking.zap.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedBarsAutoHideTest {
    private val t = 50

    /** Mid-list with a baseline and zero travel. */
    private fun scrolling(): FeedBarsAutoHide {
        val gate = FeedBarsAutoHide(t)
        gate.onScroll(index = 5, offset = 100, atTop = false, scrolling = false) // baseline
        return gate
    }

    @Test fun scrollDownPastThreshold_hides() {
        val gate = scrolling()
        assertNull(gate.onScroll(5, 100 + t, false, true))
        assertEquals(false, gate.onScroll(5, 100 + t + 1, false, true))
    }

    @Test fun scrollUpPastThreshold_shows() {
        val gate = scrolling()
        assertEquals(true, gate.onScroll(5, 100 - t - 1, false, true))
    }

    @Test fun itemJump_countsAsFullThreshold() {
        val gate = scrolling()
        assertNull(gate.onScroll(6, 0, false, true))
        assertEquals(false, gate.onScroll(6, 1, false, true))
    }

    @Test fun atTop_alwaysShows() {
        val gate = scrolling()
        assertEquals(true, gate.onScroll(0, 0, atTop = true, scrolling = true))
    }

    @Test fun idle_resetsDelta() {
        val gate = scrolling()
        gate.onScroll(5, 140, false, true)
        gate.onScroll(5, 140, false, false)
        assertEquals(0, gate.scrollDelta)
    }

    @Test fun restoredMidFling_leftoverTravelCannotHide() {
        val gate = scrolling()
        gate.onScroll(5, 140, false, true)
        gate.restored()
        assertTrue(gate.holdUntilNewTouch)
        var offset = 140
        repeat(20) { step ->
            offset += 40
            assertNull("fling step $step", gate.onScroll(5 + step / 5, offset, false, true))
        }
        assertTrue(gate.scrollDelta <= 0)
    }

    @Test fun restored_holdSurvivesFlingEnd_untilNewTouch() {
        val gate = scrolling()
        gate.restored()
        gate.onScroll(5, 200, false, false) // fling ended
        assertTrue("the fling ending is not a new touch", gate.holdUntilNewTouch)
        gate.newTouch()
        assertFalse(gate.holdUntilNewTouch)
        gate.onScroll(5, 200, false, true)
        assertEquals(false, gate.onScroll(5, 200 + t + 1, false, true))
    }

    @Test fun restored_hold_onlyBlocksHiding() {
        val gate = scrolling()
        gate.restored()
        assertEquals(true, gate.onScroll(0, 0, atTop = true, scrolling = true))
    }
}
