package cooking.zap.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.FoodHashtags
import cooking.zap.app.nostr.OnlyFoodCompose

/**
 * The full food-tag set behind the composer row's "+": every tag the
 * OnlyFood filter matches on ([FoodHashtags.ALL]), "Popular" first (the
 * suggestion pills), then the rest alphabetically, with a search field.
 *
 * Why this set and not the food-topics taxonomy: a tag from this list is
 * what makes a note reachable in OnlyFood. Taps go through the same
 * [cooking.zap.app.viewmodel.ComposeViewModel.toggleFoodTag] as the row, so
 * the body stays the single source of truth and the cap applies.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FoodTagPickerSheet(
    tagCount: Int,
    overCap: Boolean,
    isSelected: (String) -> Boolean,
    atCap: Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val popular = OnlyFoodCompose.SUGGESTED_TAGS.filter { it.contains(trimmed, ignoreCase = true) }
    val popularSet = OnlyFoodCompose.SUGGESTED_TAGS.toSet()
    val rest = FoodHashtags.ALL
        .filter { it !in popularSet && it.contains(trimmed, ignoreCase = true) }
        .sorted()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.tag_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.tag_count_label, tagCount, OnlyFoodCompose.MAX_TAGS),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overCap) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.tag_picker_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (popular.isEmpty() && rest.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tag_picker_empty, query.trim()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (popular.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.tag_picker_popular),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TagChipFlow(popular, isSelected, atCap, onToggle)
                }
                if (rest.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.tag_picker_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    TagChipFlow(rest, isSelected, atCap, onToggle)
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 16.dp)
            ) {
                Text(stringResource(R.string.btn_done), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipFlow(
    tags: List<String>,
    isSelected: (String) -> Boolean,
    atCap: Boolean,
    onToggle: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        tags.forEach { tag ->
            val selected = isSelected(tag)
            FilterChip(
                selected = selected,
                onClick = { onToggle(tag) },
                enabled = selected || !atCap,
                label = { Text("#$tag") }
            )
        }
    }
}
