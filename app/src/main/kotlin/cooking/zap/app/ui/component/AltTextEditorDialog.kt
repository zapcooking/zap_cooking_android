package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.FeatureFlags
import cooking.zap.app.R
import cooking.zap.app.api.AltTextResult

/**
 * Live state of an alt editor's "Generate with AI" request, keyed to one
 * image URL. Shared by the note and recipe composers' view models.
 */
data class AltTextGeneration(
    val url: String,
    val running: Boolean,
    val result: AltTextResult? = null
)

/**
 * The composer's alt-text editor (alt-text handoff §3): image preview, a
 * one-line explainer, a capped multiline field with a remaining-count, and —
 * gated by [FeatureFlags.ALT_TEXT_AI_ENABLED] — the Cook+ "Generate with AI"
 * action (§4). The generated description is written into the field as an
 * editable draft; nothing publishes sight-unseen.
 */
@Composable
fun AltTextEditorDialog(
    url: String,
    initialAlt: String,
    generation: AltTextGeneration?,
    onGenerate: () -> Unit,
    onConsumeGeneration: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(initialAlt)) }
    var seenGeneration by remember(url) { mutableStateOf(false) }

    // Place a freshly generated description into the field as an editable
    // draft (never auto-save, never auto-publish). Re-opening the dialog with
    // a stale result in flight doesn't re-apply it: [seenGeneration] tracks
    // the request this dialog instance dispatched.
    LaunchedEffect(generation?.url, generation?.result) {
        val gen = generation ?: return@LaunchedEffect
        if (gen.url != url) return@LaunchedEffect
        if (gen.running) {
            seenGeneration = true
        } else if (seenGeneration && gen.result != null) {
            if (gen.result is AltTextResult.Success) {
                field = TextFieldValue(gen.result.description, TextRange(gen.result.description.length))
            }
            onConsumeGeneration()
        }
    }

    fun applyInput(value: TextFieldValue): TextFieldValue {
        // Hard cap mirrors the web editor: 2000 chars, count shown below.
        val overflow = value.text.length - ALT_TEXT_MAX_CHARS
        return if (overflow > 0) {
            value.copy(text = value.text.dropLast(overflow)).let {
                TextFieldValue(it.text, TextRange(it.text.length))
            }
        } else value
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_add_alt_text)) },
        text = {
            Column {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.alt_editor_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = applyInput(it) },
                    placeholder = { Text(stringResource(R.string.alt_editor_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.alt_editor_chars_remaining,
                            ALT_TEXT_MAX_CHARS - field.text.length
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (initialAlt.isNotEmpty()) {
                        TextButton(onClick = { onSave("") }) {
                            Text(stringResource(R.string.alt_editor_clear))
                        }
                    }
                }
                GenerationRow(
                    url = url,
                    generation = generation,
                    canGenerate = FeatureFlags.ALT_TEXT_AI_ENABLED && url.startsWith("http"),
                    onGenerate = onGenerate
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }) {
                Text(stringResource(R.string.btn_save), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun GenerationRow(
    url: String,
    generation: AltTextGeneration?,
    canGenerate: Boolean,
    onGenerate: () -> Unit
) {
    if (!canGenerate) return
    val running = generation?.url == url && generation.running
    val result = generation?.takeIf { it.url == url && !it.running }?.result

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onGenerate, enabled = !running) {
                Text(stringResource(R.string.alt_generate_with_ai))
            }
            // Cook+ badge — the action is membership-gated server-side (fails closed).
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = stringResource(R.string.alt_cookplus_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        // Generation progress on its own line — inline with the button it
        // gets crunched inside the dialog's width.
        if (running) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 2.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.alt_generating),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val errorMessage = when (result) {
        is AltTextResult.NotMember -> stringResource(R.string.alt_error_upsell)
        is AltTextResult.RateLimited -> stringResource(R.string.alt_error_rate_limited)
        is AltTextResult.ImageUnreadable -> stringResource(R.string.alt_error_unreadable)
        is AltTextResult.SignFailed -> stringResource(R.string.alt_error_sign)
        is AltTextResult.MembershipUnavailable,
        is AltTextResult.Error -> stringResource(R.string.alt_error_network)
        null, is AltTextResult.Success -> null
    }
    if (errorMessage != null) {
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
