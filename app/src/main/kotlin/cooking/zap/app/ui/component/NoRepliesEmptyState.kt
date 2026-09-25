package cooking.zap.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cooking.zap.app.R

/**
 * The Zc brand mark — pan ring + handle (with hang-hole) and the Zap bolt
 * sitting in the lens — for the thread's "No replies yet" dead end
 * (ANDROID_PORT_NO_REPLIES_MARK.md; iOS zapcooking_ios#139).
 *
 * A dead end should whisper: the mark draws in **one fixed, low-contrast
 * grey** ([NO_REPLIES_TINT], neutral `#808080` at 50% alpha). One fixed grey
 * has no light/dark variant to strand on a same-coloured ground, and the
 * composite is mathematically capped at 64/255 max-channel points off ANY
 * ground (`delta = |128 − channel| × 0.5`) — visible everywhere, loud
 * nowhere.
 *
 * The two paths are drawn explicitly (ring first, bolt second), both
 * even-odd: templating or tint-flattening a combined asset drops the bolt,
 * which lives inside the lens hole of the ring path. The grey is applied as
 * a tint at draw time, never baked into path data.
 */
internal const val NO_REPLIES_TINT = 0x80808080

/** viewBox 0 0 456 456. Ring + handle; the lens and hang-hole are even-odd sub-path holes. */
internal const val ZC_RING_PATH_DATA =
    "M190.68,381.35 C227.22,381.35 261.36,371.07 290.36,353.25 C315.02,338.1 329.21,350.55 339.92,362.99 L412.89,447.74 C421.03,457.19 435.48,457.73 444.3,448.91 L448.93,444.28 C457.75,435.46 457.21,421.01 447.76,412.87 L363.01,339.9 C350.57,329.19 338.11,315 353.27,290.34 C371.09,261.34 381.37,227.2 381.37,190.66 C381.37,154.12 371.11,120.05 353.32,91.06 C350.3,86.15 349.35,80.28 349.14,74.52 C348.77,64.18 344.36,53.59 336.06,45.28 C327.76,36.98 317.16,32.57 306.83,32.2 C301.07,31.99 295.21,31.04 290.29,28.02 C261.28,10.26 227.18,0 190.68,0 C138.03,0 90.36,21.34 55.85,55.85 C21.34,90.35 0,138.02 0,190.68 C0,227.18 10.26,261.29 28.05,290.27 C31.07,295.19 32.02,301.05 32.23,306.81 C32.6,317.15 37.01,327.74 45.31,336.05 C53.61,344.35 64.21,348.76 74.55,349.13 C80.31,349.34 86.17,350.29 91.09,353.31 C120.08,371.1 154.18,381.36 190.68,381.36 L190.68,381.35 Z M398.99,398.98 C397.13,400.84 396.96,403.81 398.61,405.86 L419.91,432.46 C423.24,436.62 429.44,436.96 433.21,433.19 C436.98,429.42 436.63,423.22 432.48,419.89 L405.88,398.59 C403.82,396.94 400.86,397.11 399,398.97 L398.99,398.98 Z M302.32,302.31 C240.67,363.96 140.71,363.96 79.05,302.31 C17.4,240.66 17.4,140.7 79.05,79.04 C140.7,17.39 240.66,17.39 302.32,79.04 C363.97,140.69 363.97,240.65 302.32,302.31 Z"

/** viewBox 0 0 456 456. The Zap bolt — drawn AFTER the ring, a separate path so it can never be flattened away. */
internal const val ZC_BOLT_PATH_DATA =
    "M256.47,167.22 C255.44,165.04 253.31,163.69 250.9,163.69 L208.99,163.69 L208.79,163.43 L225.04,100.91 C225.78,98.04 224.51,95.21 221.87,93.87 C219.23,92.53 216.2,93.16 214.32,95.45 L125.94,203.16 C124.41,205.02 124.1,207.53 125.13,209.7 C126.16,211.88 128.29,213.23 130.7,213.23 L172.61,213.23 L172.81,213.49 L156.56,276.01 C155.82,278.88 157.09,281.71 159.73,283.05 C160.65,283.52 161.62,283.75 162.57,283.75 C164.34,283.75 166.06,282.96 167.28,281.47 L255.66,173.76 C257.19,171.9 257.5,169.39 256.47,167.22 Z"

private val VIEW_BOX = 456f

// Parsed once at class-load (same discipline as CheffyIcon — this can render
// in lists). Both paths use even-odd fills; the ring's lens and hang-hole
// are sub-path holes and the bolt draws on top of the lens.
private fun parseZc(d: String): Path =
    PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd }

private val RING = parseZc(ZC_RING_PATH_DATA)
private val BOLT = parseZc(ZC_BOLT_PATH_DATA)

/**
 * The Zc mark in the one quiet grey. Purely decorative — no semantics, so
 * the surrounding empty state reads only its label.
 */
@Composable
fun NoRepliesMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val tint = Color(NO_REPLIES_TINT)
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / VIEW_BOX
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPath(RING, color = tint, style = Fill)
            drawPath(BOLT, color = tint, style = Fill)
        }
    }
}

/**
 * The thread's "No replies yet" dead end: the Zc mark in one quiet grey,
 * the label below in the theme's tertiary content colour. Merged semantics
 * so TalkBack reads just "No replies yet" for the whole block.
 */
@Composable
fun NoRepliesEmptyState(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        NoRepliesMark()
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Text(
            text = androidx.compose.ui.res.stringResource(R.string.thread_no_replies),
            style = MaterialTheme.typography.bodyMedium,
            // Closest tertiary-content equivalent of the iOS spec.
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}
