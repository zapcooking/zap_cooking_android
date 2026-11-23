package cooking.zap.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Feed chrome auto-hide: the scroll-direction detector behind
 * [cooking.zap.app.viewmodel.FeedViewModel.barsVisible]. Scrolling down past
 * [threshold] pixels of cumulative travel hides the top/bottom bars, scrolling
 * up past it (or reaching the top) shows them. An index change counts as a
 * whole threshold, since `LazyListState`'s offset resets at item boundaries;
 * travel is clamped at ±2× so a long drag can't stockpile.
 *
 * [restored] is the restore circle's tap: it starts the count over and holds
 * hiding until the next finger drag ([newTouch]), so whatever is left of the
 * fling the tap interrupted can't collapse the bars it just brought back.
 */
class FeedBarsAutoHide(val threshold: Int = 50) {
    var scrollDelta = 0
        private set
    var holdUntilNewTouch = false
        private set
    private var prevIndex = 0
    private var prevOffset = 0

    /** One list position update. Returns the visibility the bars should take, or null to leave them. */
    fun onScroll(index: Int, offset: Int, atTop: Boolean, scrolling: Boolean): Boolean? {
        val verdict: Boolean? = when {
            atTop -> {
                scrollDelta = 0
                true
            }
            scrolling -> {
                val delta = when {
                    index > prevIndex -> threshold // item jumped, treat as big scroll down
                    index < prevIndex -> -threshold
                    else -> offset - prevOffset
                }
                scrollDelta = (scrollDelta + delta).coerceIn(-threshold * 2, threshold * 2)
                if (holdUntilNewTouch) {
                    // Down travel doesn't bank while held; up still shows.
                    scrollDelta = minOf(scrollDelta, 0)
                    if (scrollDelta < -threshold) true else null
                } else when {
                    scrollDelta > threshold -> false
                    scrollDelta < -threshold -> true
                    else -> null
                }
            }
            else -> {
                scrollDelta = 0
                null
            }
        }
        prevIndex = index
        prevOffset = offset
        return verdict
    }

    fun restored() {
        scrollDelta = 0
        holdUntilNewTouch = true
    }

    fun newTouch() {
        holdUntilNewTouch = false
    }
}

/** Same amber as the bottom bar's unread dots. */
private val RestoreUnreadDotColor = Color(0xFFFBBF24)

const val FEED_BARS_RESTORE_TAG = "feed-bars-restore"

/**
 * The minimized tab bar: while the feed's bars are collapsed, a small circle
 * bottom-left (opposite the compose FAB, centers level) with a generic menu
 * glyph brings both bars back on tap. It carries one unread dot for
 * Messages / Notifications, whose own dots go away with the tab row. It
 * restores the bars; it never switches tabs.
 */
@Composable
fun FeedBarsRestoreButton(
    hasUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier.testTag(FEED_BARS_RESTORE_TAG)) {
        Box(
            modifier = Modifier
                .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                .size(FEED_BARS_RESTORE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    role = Role.Button,
                    onClickLabel = "Show tab bar",
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = if (hasUnread) "Show tab bar, unread activity" else "Show tab bar",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        if (hasUnread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(8.dp)
                    .background(RestoreUnreadDotColor, CircleShape)
            )
        }
    }
}

val FEED_BARS_RESTORE_SIZE = 44.dp
