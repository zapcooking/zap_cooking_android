package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cooking.zap.app.R

/**
 * Shared Nourish affordances used by recipe detail and the Nourish hub.
 * Keeping these in one place avoids UI/copy drift between screens.
 */
@Composable
fun NourishComputePanel(
    onCompute: () -> Unit,
    computing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = stringResource(R.string.nourish_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.nourish_compute_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Button(onClick = onCompute, enabled = !computing) {
            if (computing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.nourish_compute_loading))
            } else {
                Text(stringResource(R.string.nourish_compute_action))
            }
        }
    }
}

@Composable
fun NourishMessagePanel(
    message: String,
    retry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = stringResource(R.string.nourish_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (retry != null) {
            TextButton(onClick = retry) { Text(stringResource(R.string.btn_retry)) }
        }
    }
}
