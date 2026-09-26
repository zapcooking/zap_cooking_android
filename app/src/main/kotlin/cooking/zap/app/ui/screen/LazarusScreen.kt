package cooking.zap.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cooking.zap.app.R
import cooking.zap.app.lazarus.LazarusListItem
import cooking.zap.app.lazarus.LazarusKindProfile
import cooking.zap.app.lazarus.LazarusWarning
import cooking.zap.app.lazarus.getLazarusItemRange
import cooking.zap.app.viewmodel.LazarusRestoreState
import cooking.zap.app.viewmodel.LazarusUiState
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.viewmodel.LazarusViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Data recovery (Lazarus): scan the relay set for superseded
 * versions of the account's replaceable lists, rank them (clobber detection,
 * not size envy), and restore through an explicit signed click.
 *
 * Safety posture follows the frontend PR's Copilot findings as precedent:
 * private deltas are decrypted before a restore can be approved (a
 * candidate whose encrypted content can't be decrypted keeps restore
 * disabled), the pre-sign re-read fails closed, and the success message
 * counts only relays that acknowledged the event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazarusScreen(
    viewModel: LazarusViewModel,
    pubkey: String?,
    signer: NostrSigner?,
    onBack: () -> Unit
) {
    val selectedKind by viewModel.selectedKind.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val selectedCandidate by viewModel.selectedCandidate.collectAsState()
    val delta by viewModel.delta.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()
    val undecryptable by viewModel.undecryptable.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lazarus_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedKind != null) viewModel.backToKinds() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                selectedKind == null -> KindList(viewModel, uiState, pubkey)
                else -> RecoveryFlow(
                    viewModel, selectedKind!!, uiState, selectedCandidate, delta,
                    restoreState, undecryptable, pubkey, signer
                )
            }
        }
    }

    // Restore confirm dialog: the one place a recovery is approved.
    if (selectedCandidate != null && restoreState is LazarusRestoreState.Working == false &&
        restoreState is LazarusRestoreState.Idle == false
    ) {
        val state = restoreState
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreState() },
            title = {
                Text(
                    when (state) {
                        is LazarusRestoreState.Published -> stringResource(R.string.lazarus_restored_title)
                        is LazarusRestoreState.Changed -> stringResource(R.string.lazarus_changed_title)
                        is LazarusRestoreState.WrongAccount -> stringResource(R.string.lazarus_wrong_account_title)
                        is LazarusRestoreState.Failed -> stringResource(R.string.lazarus_failed_title)
                        is LazarusRestoreState.Working -> stringResource(R.string.lazarus_restoring)
                        LazarusRestoreState.Idle -> ""
                    }
                )
            },
            text = {
                Text(
                    when (state) {
                        is LazarusRestoreState.Published ->
                            stringResource(
                                R.string.lazarus_restored_body,
                                state.acceptedRelays,
                                if (state.bestEffortRelays > 0)
                                    " (plus best-effort on ${state.bestEffortRelays})"
                                else ""
                            )
                        is LazarusRestoreState.Changed ->
                            stringResource(R.string.lazarus_changed_body)
                        is LazarusRestoreState.WrongAccount ->
                            stringResource(R.string.lazarus_wrong_account_body)
                        is LazarusRestoreState.Failed -> state.message
                        is LazarusRestoreState.Working ->
                            stringResource(R.string.lazarus_restoring)
                        LazarusRestoreState.Idle -> ""
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRestoreState() }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }
}

@Composable
private fun KindList(
    viewModel: LazarusViewModel,
    uiState: LazarusUiState,
    pubkey: String?
) {
    val kinds = viewModel.kinds
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.lazarus_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        var lastTier = 0
        kinds.forEach { profile ->
            if (profile.tier != lastTier) {
                lastTier = profile.tier
                item(key = "tier-$lastTier") {
                    Text(
                        stringResource(
                            when (lastTier) {
                                1 -> R.string.lazarus_tier1
                                2 -> R.string.lazarus_tier2
                                else -> R.string.lazarus_tier3
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                }
            }
            item(key = "kind-${profile.kind}") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            pubkey?.let { viewModel.selectKind(profile, it) }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.lazarus_scan_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState is LazarusUiState.Scanning && uiState.kind == profile.kind) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryFlow(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    uiState: LazarusUiState,
    selectedCandidate: cooking.zap.app.lazarus.LazarusCandidate?,
    delta: cooking.zap.app.lazarus.LazarusDelta?,
    restoreState: LazarusRestoreState,
    undecryptable: Set<String>,
    pubkey: String?,
    signer: NostrSigner?
) {
    when (uiState) {
        is LazarusUiState.Scanning, LazarusUiState.Idle -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.lazarus_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is LazarusUiState.Failed -> Box(
            Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                uiState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        is LazarusUiState.Done -> VersionList(
            viewModel, profile, uiState, selectedCandidate, delta, restoreState, undecryptable, pubkey, signer
        )
    }
}

@Composable
private fun VersionList(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    state: LazarusUiState.Done,
    selectedCandidate: cooking.zap.app.lazarus.LazarusCandidate?,
    delta: cooking.zap.app.lazarus.LazarusDelta?,
    restoreState: LazarusRestoreState,
    undecryptable: Set<String>,
    pubkey: String?,
    signer: NostrSigner?
) {
    val scan = state.scan
    val fmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val hidePastEmpty = remember(scan.kind) { mutableStateOf(scan.candidates.size > 12) }
    val expandedGroups = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item(key = "summary") {
            Text(
                stringResource(
                    R.string.lazarus_scan_summary,
                    scan.respondingRelays.size,
                    scan.queriedRelays.size,
                    scan.candidates.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            if (scan.olderCursors.isNotEmpty()) {
                TextButton(onClick = { viewModel.loadOlder() }) {
                    Text(stringResource(R.string.lazarus_load_older))
                }
            }
        }

        scan.recommended?.let { recommended ->
            item(key = "recommended") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.lazarus_recommended_banner),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        val range = getLazarusItemRange(recommended.itemCount)
                        val currentRange = scan.current?.let { getLazarusItemRange(it.itemCount) }
                        Text(
                            stringResource(
                                R.string.lazarus_recommended_detail,
                                describeCount(range.min, range.max),
                                currentRange?.let { describeCount(it.min, it.max) } ?: "?"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        val items = cooking.zap.app.lazarus.groupLazarusCandidates(scan, profile, hidePastEmpty.value)
        items(items.size, key = { i ->
            when (val item = items[i]) {
                is LazarusListItem.Version -> item.candidate.event.id
                is LazarusListItem.Group -> item.candidates.first().event.id + "-grp"
            }
        }) { i ->
            when (val item = items[i]) {

                is LazarusListItem.Version -> VersionRow(
                    candidate = item.candidate,
                    label = describeCount(
                        getLazarusItemRange(item.candidate.itemCount).min,
                        getLazarusItemRange(item.candidate.itemCount).max
                    ),
                    fmt = fmt,
                    expanded = selectedCandidate?.event?.id == item.candidate.event.id,
                    onClick = {
                        viewModel.selectCandidate(
                            if (selectedCandidate?.event?.id == item.candidate.event.id) null else item.candidate
                        )
                    },
                    deltaSlot = deltaSlotFor(
                        viewModel, profile, item.candidate, delta, undecryptable,
                        restoreState, pubkey, signer
                    )
                )

                is LazarusListItem.Group -> {
                    val index = i
                    var expandedNow = expandedGroups[index] == true
                    Column {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clickable { expandedGroups[index] = !expandedNow },
                            colors = CardDefaults.cardColors(
                                containerColor = if (item.clobbered)
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(
                                            if (item.clobbered) R.string.lazarus_group_clobber
                                            else R.string.lazarus_group_edits,
                                            item.candidates.size
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        fmt.format(Date(item.candidates.first().event.created_at * 1000)) +
                                            " — " +
                                            fmt.format(Date(item.candidates.last().event.created_at * 1000)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    if (expandedNow) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }
                        if (expandedNow) {
                            item.candidates.forEach { candidate ->
                                VersionRow(
                                    candidate = candidate,
                                    label = describeCount(
                                        getLazarusItemRange(candidate.itemCount).min,
                                        getLazarusItemRange(candidate.itemCount).max
                                    ),
                                    fmt = fmt,
                                    expanded = selectedCandidate?.event?.id == candidate.event.id,
                                    indented = true,
                                    onClick = {
                                        viewModel.selectCandidate(
                                            if (selectedCandidate?.event?.id == candidate.event.id) null else candidate
                                        )
                                    },
                                    deltaSlot = deltaSlotFor(
                                        viewModel, profile, candidate, delta, undecryptable,
                                        restoreState, pubkey, signer
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun VersionRow(
    candidate: cooking.zap.app.lazarus.LazarusCandidate,
    label: String,
    fmt: SimpleDateFormat,
    expanded: Boolean,
    indented: Boolean = false,
    onClick: () -> Unit,
    deltaSlot: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 16.dp else 0.dp, bottom = 8.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    candidate.isRecommended -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    candidate.isCurrent -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        fmt.format(Date(candidate.event.created_at * 1000)) +
                            "  ·  " + candidate.foundOn.size + " relays",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (candidate.isCurrent) {
                    Badge(stringResource(R.string.lazarus_badge_current))
                } else if (candidate.isRecommended) {
                    Badge(stringResource(R.string.lazarus_badge_recommended))
                }
            }
        }
        // The delta panel opens INLINE, right under the row the user clicked.
        if (expanded) deltaSlot?.invoke()
    }
}

// The panel needs the profile for warnings; derive it from the event kind.
private fun profileForCandidate(candidate: cooking.zap.app.lazarus.LazarusCandidate): LazarusKindProfile? =
    cooking.zap.app.lazarus.getLazarusKindProfile(candidate.event.kind)

@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun describeCount(min: Int, max: Int): String =
    if (min == max) "$min" else "$min–$max"

/** The delta panel for the selected row, or null for the current version
 *  (restoring the current into itself is meaningless) or any other row. */
@Composable
private fun deltaSlotFor(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    candidate: cooking.zap.app.lazarus.LazarusCandidate,
    delta: cooking.zap.app.lazarus.LazarusDelta?,
    undecryptable: Set<String>,
    restoreState: LazarusRestoreState,
    pubkey: String?,
    signer: NostrSigner?
): (@Composable () -> Unit)? {
    if (candidate.isCurrent) return null
    return {
        // The VM's delta flow is computed for the selected candidate, which
        // is exactly the expanded row.
        DeltaPanel(
            profile = profile,
            candidate = candidate,
            delta = delta,
            undecryptable = candidate.event.id in undecryptable,
            working = restoreState is LazarusRestoreState.Working,
            onRestore = {
                if (pubkey != null && signer != null) viewModel.restore(candidate, signer, pubkey)
            },
            onDismiss = { viewModel.selectCandidate(null) }
        )
    }
}

@Composable
private fun DeltaPanel(
    profile: LazarusKindProfile,
    candidate: cooking.zap.app.lazarus.LazarusCandidate,
    delta: cooking.zap.app.lazarus.LazarusDelta?,
    undecryptable: Boolean,
    working: Boolean,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    if (delta == null) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.lazarus_delta_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            }
            Text(
                stringResource(
                    R.string.lazarus_delta_counts,
                    delta.addedCount, delta.removedCount
                ) + when {
                    delta.grows -> "  ·  " + stringResource(R.string.lazarus_delta_grows)
                    delta.shrinks -> "  ·  " + stringResource(R.string.lazarus_delta_shrinks)
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (delta.privateUnknown || undecryptable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (undecryptable) stringResource(R.string.lazarus_private_undecryptable)
                    else stringResource(R.string.lazarus_private_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            profile?.requiredWarnings?.forEach { warning ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        when (warning) {
                            LazarusWarning.REMUTE -> R.string.lazarus_warn_remute
                            LazarusWarning.STALE_RELAYS -> R.string.lazarus_warn_stale_relays
                            LazarusWarning.AFFECTS_OTHERS -> R.string.lazarus_warn_affects_others
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                var confirmShrink by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        // A shrink needs its own explicit second confirmation.
                        if (delta.shrinks && !confirmShrink) {
                            confirmShrink = true
                        } else {
                            onRestore()
                        }
                    },
                    enabled = !working && !undecryptable,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(
                            when {
                                delta.shrinks && !confirmShrink -> R.string.lazarus_restore_shrink
                                else -> R.string.lazarus_restore
                            }
                        )
                    )
                }
            }
        }
    }
}
