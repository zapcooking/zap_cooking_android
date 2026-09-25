package cooking.zap.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * JVM port of the iOS `EmptyStateGroundTests` math
 * (ANDROID_PORT_NO_REPLIES_MARK.md, "Pixel test parity").
 *
 * This repo's unit suite has no Robolectric, so the render itself is an
 * on-device acceptance item (bolt visible in the lens, hang-hole, one flat
 * grey). What can never be allowed to drift is pinned here as pure math and
 * path-data structure: the one fixed grey, its source-over composite over
 * the two shipped grounds (visible-but-quiet, bounded BOTH ways), the
 * any-ground cap of 64, and the two-path/even-odd-hole structure that keeps
 * the Zap bolt from being flattened away.
 */
class NoRepliesMarkTest {

    private val lightGround = 0xFFD8D8D8L
    private val darkGround = 0xFF111827L

    private fun channel(argb: Long, shift: Int): Int = ((argb shr shift) and 0xFF).toInt()

    /** Source-over composite of [tint] over [ground], one channel at [shift]. */
    private fun composite(tint: Long, ground: Long, shift: Int): Int {
        val a = channel(tint, 24) / 255.0
        val t = channel(tint, shift)
        val g = channel(ground, shift)
        return (t * a + g * (1 - a)).roundToInt()
    }

    private fun maxChannelDelta(tint: Long, ground: Long): Int =
        listOf(16, 8, 0).maxOf { shift -> abs(composite(tint, ground, shift) - channel(ground, shift)) }

    @Test
    fun `tint is one flat neutral grey at 50 percent alpha`() {
        assertEquals(0x80, channel(NO_REPLIES_TINT.toLong(), 24))
        assertEquals(channel(NO_REPLIES_TINT.toLong(), 16), channel(NO_REPLIES_TINT.toLong(), 8))
        assertEquals(channel(NO_REPLIES_TINT.toLong(), 8), channel(NO_REPLIES_TINT.toLong(), 0))
        // Nothing brand-coloured: the mark cannot be near brand yellow 0xFFFFC83A.
        for (shift in listOf(16, 8, 0)) {
            assertTrue(abs(channel(NO_REPLIES_TINT.toLong(), shift) - 0xC8) > 12)
        }
    }

    @Test
    fun `composite is ground over two plus 64 on both shipped grounds`() {
        for (ground in listOf(lightGround, darkGround)) {
            for (shift in listOf(16, 8, 0)) {
                val expected = channel(ground, shift) / 2.0 + 64.0
                assertTrue(
                    abs(composite(NO_REPLIES_TINT.toLong(), ground, shift) - expected) <= 1.0
                )
            }
        }
    }

    @Test
    fun `contrast is visible but quiet on both shipped grounds`() {
        val light = maxChannelDelta(NO_REPLIES_TINT.toLong(), lightGround)
        val dark = maxChannelDelta(NO_REPLIES_TINT.toLong(), darkGround)
        assertTrue("light delta $light", light in 15..140)
        assertTrue("dark delta $dark", dark in 15..140)
    }

    @Test
    fun `composite delta is capped at 64 on ANY ground`() {
        // delta = |128 − channel| × 0.5 → max 64 at channel 0 or 255.
        var worst = 0
        for (g in 0..255) {
            val ground = 0xFF000000L or (g.toLong() shl 16) or (g.toLong() shl 8) or g.toLong()
            worst = maxOf(worst, maxChannelDelta(NO_REPLIES_TINT.toLong(), ground))
        }
        assertTrue(worst <= 64)
    }

    // --- Path-data structure: the gotcha that bit iOS ---

    @Test
    fun `ring carries sub-path holes and bolt is a separate single path`() {
        // Ring: outer silhouette + hang-hole + lens = 3+ sub-paths for even-odd.
        assertTrue("ring sub-paths", Regex("""(?<![A-Za-z])M""").findAll(ZC_RING_PATH_DATA).count() >= 3)
        // Bolt: one sub-path, and distinct from the ring — never flattened into it.
        assertEquals(1, Regex("""(?<![A-Za-z])M""").findAll(ZC_BOLT_PATH_DATA).count())
        assertTrue(ZC_RING_PATH_DATA.isNotBlank() && ZC_BOLT_PATH_DATA.isNotBlank())
        assertTrue(ZC_RING_PATH_DATA != ZC_BOLT_PATH_DATA)
    }

    @Test
    fun `path data is pre-expanded - no H or V commands`() {
        assertTrue(!Regex("""[HVhv][\d.-]""").containsMatchIn(ZC_RING_PATH_DATA))
        assertTrue(!Regex("""[HVhv][\d.-]""").containsMatchIn(ZC_BOLT_PATH_DATA))
    }
}
