package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import cooking.zap.app.R
import kotlinx.coroutines.delay

/**
 * Thumbnail that survives the just-uploaded 404 window.
 *
 * A freshly uploaded URL can 404 for a second or two while the host finishes
 * writing it; Coil tries exactly once, so a plain AsyncImage's thumbnail stays
 * blank forever. Retry [maxAttempts] times with a cache-busting query, then
 * show a visible failure — the URL is in the draft either way and will be
 * appended at publish, so a silently blank cell is a note about to ship a
 * link nobody checked.
 */
@Composable
fun RetryingAsyncImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxAttempts: Int = 3
) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, failed) {
        if (failed && attempt < maxAttempts) {
            delay(400L * (attempt + 1))
            attempt += 1
            failed = false
        }
    }

    if (attempt >= maxAttempts && failed) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = stringResource(R.string.attachment_thumbnail_failed),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.6f)
            )
        }
        return
    }

    // The query param is only on the thumbnail request — the draft keeps the
    // clean URL; the param just busts Coil's caches per attempt.
    val model = if (attempt == 0) url else url + (if ('?' in url) "&" else "?") + "zc-retry=$attempt"
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onState = { state ->
            failed = state is AsyncImagePainter.State.Error
        }
    )
}
