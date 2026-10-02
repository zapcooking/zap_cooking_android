package cooking.zap.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.lazarus.LazarusCandidate
import cooking.zap.app.lazarus.LazarusDelta
import cooking.zap.app.lazarus.LazarusKindProfile
import cooking.zap.app.lazarus.LazarusListItem
import cooking.zap.app.lazarus.LazarusProfileChange
import cooking.zap.app.lazarus.LazarusPublishReport
import cooking.zap.app.lazarus.LazarusRanking
import cooking.zap.app.lazarus.LazarusRelayListStatus
import cooking.zap.app.lazarus.LazarusRelayOutcome
import cooking.zap.app.lazarus.LazarusRestoreStage
import cooking.zap.app.lazarus.LazarusScanResult
import cooking.zap.app.lazarus.LazarusSortOrder
import cooking.zap.app.lazarus.LazarusWarning
import cooking.zap.app.lazarus.countItemTags
import cooking.zap.app.lazarus.getContentEncryption
import cooking.zap.app.lazarus.getLazarusItemRange
import cooking.zap.app.lazarus.groupLazarusCandidates
import cooking.zap.app.lazarus.isLazarusSizeKnown
import cooking.zap.app.lazarus.isPastEmptyVersion
import cooking.zap.app.lazarus.lazarusScanReachedNoRelay
import cooking.zap.app.lazarus.lazarusUnansweredRelays
import cooking.zap.app.lazarus.sortLazarusCandidates
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.Nip65
import cooking.zap.app.nostr.hexToByteArray
import cooking.zap.app.viewmodel.LazarusRestoreState
import cooking.zap.app.viewmodel.LazarusUiState
import cooking.zap.app.viewmodel.LazarusViewModel
import cooking.zap.app.viewmodel.lazarusGroupKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Data recovery (Lazarus): scan the relay set for superseded
 * versions of the account's replaceable lists, rank them (clobber
 * detection, not size envy), and restore through an explicit signed tap.
 * Follows the spec's UI contract: scans only on the user's action, shows
 * every version with its relays and each relay's outcome, says plainly when
 * current couldn't be confirmed or no relay answered, and offers restore
 * only to accounts that can sign. The review opens on its own panel, the
 * way the iOS app does it.
 * Spec: https://github.com/dmnyc/lazarus/blob/main/SPEC.md
 *
 * [initialKind] opens one kind directly (the profile's Restore opens the
 * follow list); back then leaves the screen instead of returning to the
 * kind list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazarusScreen(
    viewModel: LazarusViewModel,
    initialKind: Int? = null,
    onBack: () -> Unit
) {
    val selectedKind by viewModel.selectedKind.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()
    val selected by viewModel.selectedCandidate.collectAsState()

    if (initialKind != null) {
        LaunchedEffect(initialKind) { viewModel.openInitialKind(initialKind) }
    }
    // A restore in flight finishes here: leaving could cut its publish short
    // before the app's own copy is updated
    val restoring = restoreState is LazarusRestoreState.Working
    val reviewing = selected != null
    val backToKinds = selectedKind != null && initialKind == null
    BackHandler(enabled = restoring) { }
    BackHandler(enabled = reviewing && !restoring) { viewModel.selectCandidate(null) }
    BackHandler(enabled = backToKinds && !reviewing && !restoring) { viewModel.backToKinds() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            reviewing -> stringResource(R.string.lazarus_review_title)
                            selectedKind != null -> selectedKind?.let { kindLabel(it) }
                                ?: stringResource(R.string.lazarus_title)
                            else -> stringResource(R.string.lazarus_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                reviewing -> viewModel.selectCandidate(null)
                                backToKinds -> viewModel.backToKinds()
                                else -> onBack()
                            }
                        },
                        enabled = !restoring
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val profile = selectedKind
            if (profile == null) {
                KindList(viewModel)
            } else {
                KindPage(viewModel, profile, uiState)
            }
            // The review fills the screen: its own panel above the results,
            // like the iOS app's pushed review screen.
            if (reviewing && profile != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReviewScreen(viewModel, profile)
                }
            }
        }
    }
}

@Composable
private fun kindLabel(profile: LazarusKindProfile): String = when (profile.kind) {
    3 -> stringResource(R.string.lazarus_kind_3)
    10000 -> stringResource(R.string.lazarus_kind_10000)
    0 -> stringResource(R.string.lazarus_kind_0)
    10003 -> stringResource(R.string.lazarus_kind_10003)
    10044 -> stringResource(R.string.lazarus_kind_10044)
    10002 -> stringResource(R.string.lazarus_kind_10002)
    10050 -> stringResource(R.string.lazarus_kind_10050)
    10006 -> stringResource(R.string.lazarus_kind_10006)
    else -> profile.name
}

/** Short labels for the kind picker, so it wraps evenly (full names ride on the kind list and title). */
@Composable
private fun chipLabel(profile: LazarusKindProfile): String = when (profile.kind) {
    3 -> stringResource(R.string.lazarus_chip_3)
    10000 -> stringResource(R.string.lazarus_chip_10000)
    0 -> stringResource(R.string.lazarus_chip_0)
    10003 -> stringResource(R.string.lazarus_chip_10003)
    10044 -> stringResource(R.string.lazarus_chip_10044)
    10002 -> stringResource(R.string.lazarus_chip_10002)
    10050 -> stringResource(R.string.lazarus_chip_10050)
    10006 -> stringResource(R.string.lazarus_chip_10006)
    else -> profile.name
}

/**
 * The per-kind note the page shows alongside the scan: the staleness
 * warnings for the relay lists (they matter before and after a scan), and
 * the intent note for meaningful-empty kinds before any results.
 */
@Composable
private fun KindNote(profile: LazarusKindProfile, resultsShown: Boolean) {
    val text = when {
        profile.kind == 10002 -> stringResource(R.string.lazarus_kind_note_10002)
        profile.kind == 10050 -> stringResource(R.string.lazarus_kind_note_10050)
        profile.kind == 10044 && !resultsShown -> stringResource(R.string.lazarus_kind_note_10044)
        else -> return
    }
    NoticeCard(text, NoticeTone.INFO)
}

@Composable
private fun KindList(viewModel: LazarusViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item(key = "explainer") {
            Text(
                stringResource(R.string.lazarus_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        var lastTier = 0
        viewModel.kinds.forEach { profile ->
            if (profile.tier != lastTier) {
                lastTier = profile.tier
                val tier = lastTier
                item(key = "tier-$tier") {
                    Text(
                        stringResource(
                            when (tier) {
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
                        .clickable { viewModel.selectKind(profile) },
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
                            Text(kindLabel(profile), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.lazarus_scan_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kinds to switch between without going back, for the profile's direct entry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(viewModel: LazarusViewModel, selected: LazarusKindProfile, enabled: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        viewModel.kinds.forEach { profile ->
            FilterChip(
                selected = profile.kind == selected.kind,
                onClick = { if (profile.kind != selected.kind) viewModel.selectKind(profile) },
                enabled = enabled,
                label = { Text(chipLabel(profile)) }
            )
        }
    }
}

private enum class NoticeTone { INFO, WARNING, ERROR }

@Composable
private fun NoticeCard(
    text: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null
) {
    val container = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        NoticeTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    }
    val content = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = content)
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    enabled = actionEnabled,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun KindPage(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    uiState: LazarusUiState
) {
    val restoreState by viewModel.restoreState.collectAsState()
    val canSign by viewModel.canSign.collectAsState()
    val busy = uiState is LazarusUiState.Scanning || restoreState is LazarusRestoreState.Working
    when (uiState) {
        LazarusUiState.Idle -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            KindChips(viewModel, profile, enabled = !busy)
            KindNote(profile, resultsShown = false)
            if (!canSign) {
                NoticeCard(stringResource(R.string.lazarus_view_only_scan), NoticeTone.INFO)
            }
            Text(
                stringResource(R.string.lazarus_scan_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { viewModel.scan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lazarus_scan_hint))
            }
        }
        is LazarusUiState.Scanning -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            KindChips(viewModel, profile, enabled = false)
            KindNote(profile, resultsShown = true)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.lazarus_scanning, kindLabel(profile).lowercase()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is LazarusUiState.Failed -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            KindChips(viewModel, profile, enabled = !busy)
            KindNote(profile, resultsShown = true)
            // A scan no relay answered is an error with a retry, never "nothing found",
            // and the relays' outcomes show what the scan did learn
            NoticeCard(
                text = stringResource(
                    if (uiState.noRelayAnswered) R.string.lazarus_scan_failed_no_relay
                    else R.string.lazarus_scan_failed
                ),
                tone = NoticeTone.ERROR,
                actionLabel = stringResource(R.string.lazarus_scan_again),
                onAction = { viewModel.scan() }
            )
            if (uiState.outcomes != null) {
                RelayOutcomeList(
                    queriedRelays = uiState.queriedRelays,
                    outcomes = uiState.outcomes,
                    writeRelays = emptyList(),
                    writeAreDefaults = false
                )
            }
        }
        is LazarusUiState.Done -> ScanResults(viewModel, profile, uiState.scan, restoreState, busy)
    }
}

/** A row of the version list: a version (top-level or inside an expanded group) or a group header. */
private sealed class ListRow {
    data class Version(val candidate: LazarusCandidate, val indented: Boolean) : ListRow()
    data class GroupHeader(val group: LazarusListItem.Group, val expanded: Boolean) : ListRow()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanResults(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    restoreState: LazarusRestoreState,
    busy: Boolean
) {
    val selected by viewModel.selectedCandidate.collectAsState()
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    val showPastEmpty by viewModel.showPastEmpty.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val retrying by viewModel.retrying.collectAsState()
    val loadingOlder by viewModel.loadingOlder.collectAsState()
    val olderPageFailures by viewModel.olderPageFailures.collectAsState()
    val decrypting by viewModel.decrypting.collectAsState()
    val privateTags by viewModel.privateTags.collectAsState()
    val undecryptable by viewModel.undecryptable.collectAsState()
    val canSign by viewModel.canSign.collectAsState()
    val fmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    var showRelays by rememberSaveable(scan.kind) { mutableStateOf(false) }
    var expandedFoundOn by remember { mutableStateOf<String?>(null) }

    val unanswered = lazarusUnansweredRelays(scan)
    val answeredCount = scan.relayOutcomes?.values?.count { it == LazarusRelayOutcome.ANSWERED }
        ?: scan.respondingRelays.size
    val pastEmptyCount = scan.candidates.count { isPastEmptyVersion(it, profile) }
    val rows: List<ListRow> = if (sortOrder == LazarusSortOrder.SIZE) {
        sortLazarusCandidates(scan.candidates, LazarusSortOrder.SIZE)
            .filter { showPastEmpty || !isPastEmptyVersion(it, profile) }
            .map { ListRow.Version(it, indented = false) }
    } else {
        groupLazarusCandidates(scan, profile, hidePastEmpty = !showPastEmpty).flatMap { item ->
            when (item) {
                is LazarusListItem.Version -> listOf(ListRow.Version(item.candidate, indented = false))
                is LazarusListItem.Group -> {
                    // A group holding the version under review stays open, so its row can't fold away
                    val expanded = lazarusGroupKey(item) in expandedGroups ||
                        item.candidates.any { it.event.id == selected?.event?.id }
                    listOf(ListRow.GroupHeader(item, expanded)) +
                        if (expanded) item.candidates.map { ListRow.Version(it, indented = true) } else emptyList()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item(key = "chips") { KindChips(viewModel, profile, enabled = !busy) }

        item(key = "kind-note") { KindNote(profile, resultsShown = true) }

        if (restoreState is LazarusRestoreState.Published) {
            item(key = "published") {
                PublishedCard(viewModel, restoreState.report, restoreState.localUpdated) {
                    viewModel.dismissRestoreState()
                }
            }
        }

        item(key = "summary") {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(
                    pluralStringResource(R.plurals.lazarus_versions_found, scan.candidates.size, scan.candidates.size),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showRelays = !showRelays }
                ) {
                    Text(
                        stringResource(R.string.lazarus_relays_answered, answeredCount, scan.queriedRelays.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (showRelays) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (showRelays) {
                    RelayOutcomeList(
                        queriedRelays = scan.queriedRelays,
                        outcomes = scan.relayOutcomes,
                        writeRelays = scan.writeRelays,
                        writeAreDefaults = scan.relayList == LazarusRelayListStatus.MISSING
                    )
                }
                // A quiet link next to the outcomes it retries, never styled as
                // an error: the scan result stands on its own, and this is only
                // a second pass at the relays that didn't answer.
                if (unanswered.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.retryUnanswered() },
                        enabled = !retrying && !busy,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (retrying) stringResource(R.string.lazarus_retrying)
                            else pluralStringResource(R.plurals.lazarus_retry_relays, unanswered.size, unanswered.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "notices") {
            Column {
                if (lazarusScanReachedNoRelay(scan)) {
                    NoticeCard(
                        text = stringResource(R.string.lazarus_no_relay_finished),
                        tone = NoticeTone.ERROR,
                        actionLabel = stringResource(R.string.lazarus_scan_again),
                        actionEnabled = !busy,
                        onAction = { viewModel.scan() }
                    )
                } else if (!scan.currentConfirmed) {
                    NoticeCard(
                        text = stringResource(
                            if (scan.relayList == LazarusRelayListStatus.UNKNOWN) R.string.lazarus_unconfirmed_unknown
                            else R.string.lazarus_unconfirmed_write
                        ),
                        tone = NoticeTone.WARNING,
                        actionLabel = stringResource(R.string.lazarus_scan_again),
                        actionEnabled = !busy,
                        onAction = { viewModel.scan() }
                    )
                }
                if (scan.relayList == LazarusRelayListStatus.MISSING) {
                    NoticeCard(
                        stringResource(
                            R.string.lazarus_relay_list_missing,
                            scan.writeRelays.joinToString(", ") { host(it) }
                        ),
                        NoticeTone.INFO
                    )
                }
            }
        }

        if (scan.candidates.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.lazarus_no_versions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@LazyColumn
        }

        item(key = "guidance") {
            Column {
                if (scan.requiresIntentConfirmation) {
                    NoticeCard(stringResource(R.string.lazarus_intent_notice), NoticeTone.INFO)
                } else if (profile.ranking == LazarusRanking.RECENCY) {
                    NoticeCard(stringResource(R.string.lazarus_recency_note), NoticeTone.INFO)
                } else if (scan.recommended == null && scan.currentConfirmed && !lazarusScanReachedNoRelay(scan)) {
                    // A normal result, not an error: nothing looks clobbered
                    NoticeCard(stringResource(R.string.lazarus_no_improvement), NoticeTone.INFO)
                }
            }
        }

        // The recommendation is pinned above the list so a long history can't bury it
        scan.recommended?.let { recommended ->
            item(key = "recommended-${recommended.event.id}") {
                Column {
                    Text(
                        stringResource(R.string.lazarus_recommended_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    VersionRow(
                        candidate = recommended,
                        summary = summaryFor(profile, recommended),
                        detail = detailFor(profile, recommended),
                        privateNote = privateNoteFor(profile, recommended, privateTags, undecryptable, canSign),
                        fmt = fmt,
                        decrypting = recommended.event.id in decrypting,
                        foundOnExpanded = expandedFoundOn == recommended.event.id,
                        onToggleFoundOn = {
                            expandedFoundOn = if (expandedFoundOn == recommended.event.id) null
                            else recommended.event.id
                        },
                        onReview = { viewModel.selectCandidate(recommended) },
                        reviewEnabled = !busy,
                        showReview = !isPastEmptyVersion(recommended, profile)
                    )
                }
            }
        }

        if (profile.ranking == LazarusRanking.COUNT && (scan.candidates.size > 1 || pastEmptyCount > 0)) {
            item(key = "toolbar") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    FilterChip(
                        selected = sortOrder == LazarusSortOrder.DATE,
                        onClick = { viewModel.setSortOrder(LazarusSortOrder.DATE) },
                        label = { Text(stringResource(R.string.lazarus_sort_date)) }
                    )
                    FilterChip(
                        selected = sortOrder == LazarusSortOrder.SIZE,
                        onClick = { viewModel.setSortOrder(LazarusSortOrder.SIZE) },
                        label = { Text(stringResource(R.string.lazarus_sort_size)) }
                    )
                    if (pastEmptyCount > 0) {
                        TextButton(onClick = { viewModel.setShowPastEmpty(!showPastEmpty) }) {
                            Text(
                                if (showPastEmpty) stringResource(R.string.lazarus_hide_empty)
                                else pluralStringResource(R.plurals.lazarus_show_empty, pastEmptyCount, pastEmptyCount)
                            )
                        }
                    }
                }
            }
        }

        versionRows(
            rows = rows,
            viewModel = viewModel,
            profile = profile,
            fmt = fmt,
            decrypting = decrypting,
            privateTags = privateTags,
            undecryptable = undecryptable,
            canSign = canSign,
            expandedFoundOn = expandedFoundOn,
            onToggleFoundOn = { id -> expandedFoundOn = if (expandedFoundOn == id) null else id },
            busy = busy
        )

        item(key = "scan-again") {
            OutlinedButton(
                onClick = { viewModel.scan() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.lazarus_scan_again))
            }
        }

        if (scan.olderCursors.isNotEmpty()) {
            item(key = "older") {
                OutlinedButton(
                    onClick = { viewModel.loadOlder() },
                    enabled = !loadingOlder && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        stringResource(
                            if (loadingOlder) R.string.lazarus_loading_older else R.string.lazarus_load_older
                        )
                    )
                }
            }
            if (olderPageFailures > 0) {
                item(key = "older-failures") {
                    Text(
                        pluralStringResource(R.plurals.lazarus_older_failures, olderPageFailures, olderPageFailures),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun LazyListScope.versionRows(
    rows: List<ListRow>,
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    fmt: SimpleDateFormat,
    decrypting: Set<String>,
    privateTags: Map<String, List<List<String>>>,
    undecryptable: Set<String>,
    canSign: Boolean,
    expandedFoundOn: String?,
    onToggleFoundOn: (String) -> Unit,
    busy: Boolean
) {
    rows.forEach { row ->
        when (row) {
            is ListRow.GroupHeader -> item(key = "group-${lazarusGroupKey(row.group)}") {
                GroupHeader(row.group, profile, row.expanded, fmt) {
                    viewModel.toggleGroup(lazarusGroupKey(row.group))
                }
            }
            is ListRow.Version -> item(key = row.candidate.event.id) {
                Column(Modifier.padding(start = if (row.indented) 16.dp else 0.dp)) {
                    VersionRow(
                        candidate = row.candidate,
                        summary = summaryFor(profile, row.candidate),
                        detail = detailFor(profile, row.candidate),
                        privateNote = privateNoteFor(profile, row.candidate, privateTags, undecryptable, canSign),
                        fmt = fmt,
                        decrypting = row.candidate.event.id in decrypting,
                        foundOnExpanded = expandedFoundOn == row.candidate.event.id,
                        onToggleFoundOn = { onToggleFoundOn(row.candidate.event.id) },
                        onReview = { viewModel.selectCandidate(row.candidate) },
                        reviewEnabled = !busy,
                        showReview = !row.candidate.isCurrent && !isPastEmptyVersion(row.candidate, profile)
                    )
                }
            }
        }
    }
}

/** Relay outcome dot colors: answered, timed out, failed. */
private val OutcomeAnswered = Color(0xFF4CAF50)
private val OutcomeTimedOut = Color(0xFFFF9800)
private val OutcomeFailed = Color(0xFFE53935)

@Composable
private fun RelayOutcomeList(
    queriedRelays: List<String>,
    outcomes: Map<String, LazarusRelayOutcome>?,
    writeRelays: List<String>,
    writeAreDefaults: Boolean
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        queriedRelays.forEach { url ->
            val outcome = outcomes?.get(url)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when (outcome) {
                        LazarusRelayOutcome.ANSWERED -> OutcomeAnswered
                        LazarusRelayOutcome.TIMED_OUT -> OutcomeTimedOut
                        LazarusRelayOutcome.FAILED, null -> OutcomeFailed
                    },
                    modifier = Modifier.size(7.dp)
                ) {}
                Text(
                    host(url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                )
                if (url in writeRelays) {
                    Text(
                        stringResource(if (writeAreDefaults) R.string.lazarus_relay_write_default else R.string.lazarus_relay_write),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
                Text(
                    when (outcome) {
                        LazarusRelayOutcome.ANSWERED -> stringResource(R.string.lazarus_outcome_answered)
                        LazarusRelayOutcome.TIMED_OUT -> stringResource(R.string.lazarus_outcome_timed_out)
                        LazarusRelayOutcome.FAILED, null -> stringResource(R.string.lazarus_outcome_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: LazarusListItem.Group,
    profile: LazarusKindProfile,
    expanded: Boolean,
    fmt: SimpleDateFormat,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (group.clobbered) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (group.clobbered) R.string.lazarus_group_clobber else R.string.lazarus_group_edits,
                        group.candidates.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    groupSummary(group, profile, fmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun VersionRow(
    candidate: LazarusCandidate,
    summary: String,
    detail: List<String>?,
    privateNote: String?,
    fmt: SimpleDateFormat,
    decrypting: Boolean,
    foundOnExpanded: Boolean,
    onToggleFoundOn: () -> Unit,
    onReview: () -> Unit,
    reviewEnabled: Boolean,
    showReview: Boolean
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
            .padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        fmt.format(Date(candidate.event.created_at * 1000)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (decrypting) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                    if (candidate.isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Badge(stringResource(R.string.lazarus_badge_current))
                    } else if (candidate.isRecommended) {
                        Spacer(Modifier.width(6.dp))
                        Badge(stringResource(R.string.lazarus_badge_recommended))
                    }
                }
                if (showReview) {
                    TextButton(onClick = onReview, enabled = reviewEnabled) {
                        Text(stringResource(R.string.lazarus_review))
                    }
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (privateNote != null) {
                Text(
                    privateNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!detail.isNullOrEmpty()) {
                Column(Modifier.padding(top = 2.dp)) {
                    detail.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Text(
                if (candidate.foundOn.isEmpty()) stringResource(R.string.lazarus_found_local)
                else pluralStringResource(R.plurals.lazarus_found_on, candidate.foundOn.size, candidate.foundOn.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = candidate.foundOn.isNotEmpty(), onClick = onToggleFoundOn)
            )
            if (foundOnExpanded) {
                candidate.foundOn.forEach { url ->
                    Text(
                        host(url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Past empty versions are clobber evidence: shown, never offered for restore. */
@Composable
private fun isPastEmptyVersionShown(candidate: LazarusCandidate): Boolean = false

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

private fun describeRange(min: Int, max: Int): String = if (min == max) "$min" else "$min–$max"

/** The count half of a row's summary: per-kind item nouns, exact or a range. */
@Composable
private fun itemCountSummary(profile: LazarusKindProfile, candidate: LazarusCandidate): String {
    val count = candidate.itemCount
    val range = getLazarusItemRange(count)
    @Composable fun noun(n: Int): String = pluralStringResource(nounResource(profile), n)
    if (!isLazarusSizeKnown(count)) {
        // Flagged tier: private items could be neither decrypted nor sized
        return stringResource(
            R.string.lazarus_items_private_unknown,
            "${count.count} ${noun(count.count)}"
        )
    }
    if (range.max == 0) return stringResource(R.string.lazarus_items_empty)
    if (range.min == range.max) return "${range.min} ${noun(range.min)}"
    return stringResource(R.string.lazarus_items_estimated, range.min, range.max, noun(range.max))
}

/** A version's summary line: item counts, or a profile's name and field count. */
@Composable
private fun summaryFor(profile: LazarusKindProfile, candidate: LazarusCandidate): String {
    if (profile.kind != 0) return itemCountSummary(profile, candidate)
    val fields = profileFieldCount(candidate.event.content)
    if (fields == 0) return stringResource(R.string.lazarus_profile_empty)
    val name = profileName(candidate.event.content)
    val noun = pluralStringResource(R.plurals.lazarus_profile_field_noun, fields)
    return if (name != null) "$name  ·  $fields $noun" else "$fields $noun"
}

private fun nounResource(profile: LazarusKindProfile) = when (profile.kind) {
    3 -> R.plurals.lazarus_noun_3
    10000 -> R.plurals.lazarus_noun_10000
    10003 -> R.plurals.lazarus_noun_10003
    10044 -> R.plurals.lazarus_noun_10044
    else -> R.plurals.lazarus_noun_relay
}

/** The version's own detail lines: keys for NIP-4e lists, relays for relay lists. */
private fun detailFor(profile: LazarusKindProfile, candidate: LazarusCandidate): List<String>? {
    val values: List<String> = when (profile.kind) {
        10044 -> candidate.event.tags.filter { it.firstOrNull() == "n" && it.size >= 2 }.map { shortHex(it[1]) }
        10002 -> candidate.event.tags.filter { it.firstOrNull() == "r" && it.size >= 2 }
            .map { if (it.size >= 3) "${host(it[1])} (${it[2]})" else host(it[1]) }
        10050, 10006 -> candidate.event.tags.filter { it.firstOrNull() == "relay" && it.size >= 2 }.map { host(it[1]) }
        else -> return null
    }
    if (values.isEmpty()) return null
    return values.take(6) + if (values.size > 6) listOf("…and ${values.size - 6} more") else emptyList()
}

/**
 * The row's private-items note (NIP-51): included count once decrypted, why
 * not otherwise. Null when there's nothing encrypted to account for.
 */
@Composable
private fun privateNoteFor(
    profile: LazarusKindProfile,
    candidate: LazarusCandidate,
    privateTags: Map<String, List<List<String>>>,
    undecryptable: Set<String>,
    canSign: Boolean
): String? {
    val types = profile.privateItemTypes ?: return null
    if (getContentEncryption(candidate.event.content) == null) return null
    privateTags[candidate.event.id]?.let { tags ->
        val n = countItemTags(tags, types)
        return if (n == 0) null else stringResource(R.string.lazarus_row_private_incl, n)
    }
    if (candidate.event.id in undecryptable) return stringResource(R.string.lazarus_row_private_undecryptable)
    if (!canSign) return stringResource(R.string.lazarus_row_private_estimated)
    return null
}

@Composable
private fun groupSummary(group: LazarusListItem.Group, profile: LazarusKindProfile, fmt: SimpleDateFormat): String {
    // Sizes and dates across the group, so a folded run still says what it holds
    val ranges = group.candidates.map { getLazarusItemRange(it.itemCount) }
    val low = ranges.minOfOrNull { it.min } ?: 0
    val high = ranges.maxOfOrNull { it.max } ?: 0
    @Composable fun noun(n: Int): String = pluralStringResource(nounResource(profile), n)
    val size = if (low == high) "$low ${noun(low)}" else "$low–$high ${noun(high)}"
    val oldest = fmt.format(Date(group.candidates.last().event.created_at * 1000))
    val newest = fmt.format(Date(group.candidates.first().event.created_at * 1000))
    return "$size  ·  " + if (oldest == newest) newest else "$oldest – $newest"
}

private val rowJson = Json { ignoreUnknownKeys = true }

/** A profile version's display name, so versions can be told apart at a glance. */
private fun profileName(content: String): String? = try {
    val obj = rowJson.parseToJsonElement(content) as? JsonObject
    listOf("display_name", "name").firstNotNullOfOrNull { key ->
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
} catch (_: Exception) {
    null
}

private fun profileFieldCount(content: String): Int = try {
    (rowJson.parseToJsonElement(content) as? JsonObject)?.size ?: 0
} catch (_: Exception) {
    0
}

/** `wss://relay.example.com` → `relay.example.com`. */
private fun host(relay: String): String =
    relay.removePrefix("wss://").removePrefix("ws://")

private fun shortHex(hex: String): String =
    if (hex.length > 16) hex.take(8) + "…" + hex.takeLast(8) else hex

/** An npub, short enough for a list row. */
private fun shortNpub(hex: String): String = try {
    val npub = Nip19.npubEncode(hex.hexToByteArray())
    if (npub.length > 18) npub.take(12) + "…" + npub.takeLast(4) else npub
} catch (_: Exception) {
    shortHex(hex)
}

/** One delta item, readably: accounts as npubs, words quoted, hashtags with #, relays by host. */
private fun describeTag(tag: List<String>): String {
    val name = tag.firstOrNull() ?: return ""
    val value = tag.getOrNull(1).orEmpty()
    return when (name) {
        "p" -> "npub " + shortNpub(value)
        "e" -> "note " + shortHex(value)
        "a" -> value
        "word" -> "\u201C$value\u201D"
        "t" -> "#$value"
        "n" -> "key " + shortHex(value)
        "r" -> if (tag.size >= 3) "${host(value)} (${tag[2]})" else host(value)
        "relay" -> host(value)
        else -> listOfNotNull(name.ifEmpty { null }, value.ifEmpty { null }).joinToString(" ")
    }
}

/**
 * The review on its own panel: what the restore publishes and what it
 * changes, the direction of harm, the intent question for meaningful-empty
 * kinds, where it publishes, and the restore flow's confirmations and
 * outcomes. No restore happens without the button here.
 */
@Composable
private fun ReviewScreen(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile
) {
    val candidate = viewModel.selectedCandidate.collectAsState().value ?: return
    val uiState by viewModel.uiState.collectAsState()
    val delta by viewModel.delta.collectAsState()
    val profileChanges by viewModel.profileChanges.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()
    val restoreAttempt by viewModel.restoreAttempt.collectAsState()
    val decrypting by viewModel.decrypting.collectAsState()
    val undecryptable by viewModel.undecryptable.collectAsState()
    val canSign by viewModel.canSign.collectAsState()
    val changedSinceScan by viewModel.changedSinceScan.collectAsState()
    val scan = (uiState as? LazarusUiState.Done)?.scan
    val fmt = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val current = scan?.current
    // Confirmations re-arm whenever the review restarts against another version
    val reviewKey = candidate.event.id + ":" + (current?.event?.id ?: "")
    var armShrink by remember(reviewKey) { mutableStateOf(false) }
    var intentConfirmed by remember(reviewKey) { mutableStateOf(false) }
    // Never pre-selected, and never carried from one restore attempt to the next
    var overrideConfirmed by remember(reviewKey, restoreAttempt) { mutableStateOf(false) }

    val working = restoreState as? LazarusRestoreState.Working
    val needsIntent = profile.meaningfulEmpty
    val canTap = viewModel.restoreBlocker(candidate) == null && (!needsIntent || intentConfirmed)
    val chosenDecrypting = candidate.event.id in decrypting
    val currentDecrypting = current != null && current.event.id in decrypting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(
                R.string.lazarus_review_heading,
                kindLabel(profile).lowercase(),
                fmt.format(Date(candidate.event.created_at * 1000))
            ),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            summaryFor(profile, candidate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            when {
                current == null -> stringResource(R.string.lazarus_delta_no_current)
                current.foundOn.isEmpty() -> stringResource(
                    R.string.lazarus_delta_compared_local,
                    fmt.format(Date(current.event.created_at * 1000))
                )
                else -> stringResource(
                    R.string.lazarus_delta_compared,
                    fmt.format(Date(current.event.created_at * 1000))
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.lazarus_delta_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (changedSinceScan) {
            NoticeCard(stringResource(R.string.lazarus_changed_notice), NoticeTone.WARNING)
        }

        Section(stringResource(R.string.lazarus_delta_section)) {
            val changes = profileChanges
            if (changes != null) {
                ProfileChangeList(changes)
            } else {
                delta?.let { DeltaSummary(profile, it) }
            }
        }

        if (chosenDecrypting || currentDecrypting) {
            Text(stringResource(R.string.lazarus_decrypting), style = MaterialTheme.typography.bodySmall)
        } else {
            val d = delta
            if (d != null && d.chosenPrivateUncounted) {
                if (canSign && candidate.event.id in undecryptable) {
                    WarningText(stringResource(R.string.lazarus_private_undecryptable), error = true)
                    TextButton(
                        onClick = { viewModel.retryDecrypt(candidate) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) { Text(stringResource(R.string.lazarus_decrypt_again)) }
                } else {
                    WarningText(stringResource(R.string.lazarus_private_uncounted_chosen))
                }
            }
            if (d != null && d.currentPrivateUncounted) {
                WarningText(stringResource(R.string.lazarus_private_uncounted_current))
            }
        }

        delta?.let { d -> Warnings(profile, d) }

        if (needsIntent) {
            IntentQuestion(candidate, current, intentConfirmed) { intentConfirmed = it }
        }

        if (scan != null) {
            PublishTargets(viewModel, profile, scan, candidate)
        }

        when {
            !canSign -> NoticeCard(stringResource(R.string.lazarus_view_only), NoticeTone.INFO)
            working != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when (working.stage) {
                            LazarusRestoreStage.CONFIRMING -> R.string.lazarus_stage_confirming
                            LazarusRestoreStage.SIGNING -> R.string.lazarus_stage_signing
                            LazarusRestoreStage.PUBLISHING -> R.string.lazarus_stage_publishing
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> {
                val outcome = restoreState
                RestoreOutcome(outcome)
                when (outcome) {
                    LazarusRestoreState.ChangedLocally -> OutlinedButton(onClick = { viewModel.scan() }) {
                        Text(stringResource(R.string.lazarus_scan_again))
                    }
                    is LazarusRestoreState.Unconfirmed -> {
                        OutlinedButton(onClick = { viewModel.restore() }, enabled = canTap, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.lazarus_retry_restore), maxLines = 1)
                        }
                        // Only after a retry failed: a separate, unselected confirmation
                        if (outcome.attempts >= 2) {
                            WarningText(stringResource(R.string.lazarus_override_warning), error = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = overrideConfirmed, onCheckedChange = { overrideConfirmed = it })
                                Text(stringResource(R.string.lazarus_override_confirm), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = { viewModel.restore(allowUnconfirmed = true) },
                                enabled = canTap && overrideConfirmed,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text(stringResource(R.string.lazarus_override_button), maxLines = 1) }
                        }
                    }
                    is LazarusRestoreState.NotAccepted -> {
                        OutlinedButton(onClick = { viewModel.restore() }, enabled = canTap, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.lazarus_retry_restore), maxLines = 1)
                        }
                    }
                    else -> {
                        val d = delta
                        if (d != null && d.shrinks) {
                            // A shrink needs its own confirmation, separate from
                            // a grow — including when the shrink is only visible
                            // through current's undecrypted private items
                            val knownRemovals = d.removedCount > 0
                            if (!armShrink) {
                                Button(onClick = { armShrink = true }, enabled = canTap, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (knownRemovals)
                                            pluralStringResource(R.plurals.lazarus_restore_shrink, d.removedCount, d.removedCount)
                                        else
                                            stringResource(R.string.lazarus_restore_shrink_uncertain),
                                        maxLines = 1
                                    )
                                }
                            } else {
                                WarningText(
                                    stringResource(
                                        if (knownRemovals) R.string.lazarus_shrink_warning
                                        else R.string.lazarus_shrink_uncertain_warning
                                    ),
                                    error = true
                                )
                                OutlinedButton(
                                    onClick = { armShrink = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.btn_cancel), maxLines = 1)
                                }
                                // Button labels never wrap: the confirm is its own full-width row
                                Button(
                                    onClick = { viewModel.restore() },
                                    enabled = canTap,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) { Text(stringResource(R.string.lazarus_confirm_shrink), maxLines = 1) }
                            }
                        } else {
                            Button(onClick = { viewModel.restore() }, enabled = canTap, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.lazarus_restore), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A titled block of the review, the way the iOS panel groups its content. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            Column(Modifier.padding(12.dp)) { content() }
        }
    }
}

/**
 * Where the restore publishes, named before the button: the write relays
 * success is judged on (for a relay list, the ones the restored version
 * names), and the other relays that get a best-effort copy.
 */
@Composable
private fun PublishTargets(
    viewModel: LazarusViewModel,
    profile: LazarusKindProfile,
    scan: LazarusScanResult,
    candidate: LazarusCandidate
) {
    val missing = scan.relayList == LazarusRelayListStatus.MISSING
    val (judged, onRestoredList) = when {
        profile.kind == 10002 -> {
            val restoredWrite = Nip65.parseRelayList(candidate.event)
                .filter { it.write }
                .map { it.url }
            if (restoredWrite.isNotEmpty()) restoredWrite to true
            else viewModel.standInRelays to true
        }
        else -> scan.writeRelays to false
    }
    if (judged.isEmpty()) {
        NoticeCard(stringResource(R.string.lazarus_no_write_relays), NoticeTone.ERROR)
        return
    }
    val targets = stringResource(
        when {
            onRestoredList && judged == viewModel.standInRelays -> R.string.lazarus_targets_10002_defaults
            onRestoredList -> R.string.lazarus_targets_10002
            missing -> R.string.lazarus_targets_write_defaults
            else -> R.string.lazarus_targets_write
        },
        judged.joinToString(", ") { host(it) }
    )
    val extra = scan.respondingRelays.filter { it !in judged }.distinct()
    Text(
        targets + if (extra.isNotEmpty()) "  " +
            pluralStringResource(R.plurals.lazarus_targets_extra, extra.size, extra.size) else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun WarningText(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    )
}

@Composable
private fun DeltaSummary(profile: LazarusKindProfile, delta: LazarusDelta) {
    Text(
        stringResource(R.string.lazarus_delta_counts, delta.addedCount, delta.removedCount),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
    )
    if (delta.addedCount == 0 && delta.removedCount == 0 && !delta.privateUnknown) {
        Text(stringResource(R.string.lazarus_delta_no_change), style = MaterialTheme.typography.bodySmall)
    }
    if (delta.added.isNotEmpty()) {
        TagList(
            stringResource(
                when (profile.kind) {
                    3 -> R.string.lazarus_delta_added_3
                    10000 -> R.string.lazarus_delta_added_10000
                    else -> R.string.lazarus_delta_added
                },
                delta.addedCount
            ),
            delta.added
        )
    }
    if (delta.removed.isNotEmpty()) {
        TagList(
            stringResource(
                when (profile.kind) {
                    3 -> R.string.lazarus_delta_removed_3
                    10000 -> R.string.lazarus_delta_removed_10000
                    else -> R.string.lazarus_delta_removed
                },
                delta.removedCount
            ),
            delta.removed
        )
    }
}

@Composable
private fun TagList(title: String, tags: List<List<String>>) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        if (open) {
            tags.take(TAG_LIST_LIMIT).forEach { tag ->
                Text(
                    describeTag(tag),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (tags.size > TAG_LIST_LIMIT) {
                Text(
                    stringResource(R.string.lazarus_delta_more, tags.size - TAG_LIST_LIMIT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val TAG_LIST_LIMIT = 100

@Composable
private fun ProfileChangeList(changes: List<LazarusProfileChange>) {
    if (changes.isEmpty()) {
        Text(stringResource(R.string.lazarus_profile_no_change), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        stringResource(R.string.lazarus_profile_replaces),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val none = stringResource(R.string.lazarus_profile_absent)
    changes.forEach { change ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(change.field, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                change.from ?: none,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "→ " + (change.to ?: none),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D32),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The registry's required warnings, with the direction of harm spelled out for mute lists. */
@Composable
private fun Warnings(profile: LazarusKindProfile, delta: LazarusDelta) {
    profile.requiredWarnings.forEach { warning ->
        when (warning) {
            LazarusWarning.REMUTE -> {
                // Items the restored version has and current doesn't are re-muted
                if (delta.addedCount > 0) {
                    WarningText(pluralStringResource(R.plurals.lazarus_warn_remute, delta.addedCount, delta.addedCount), error = true)
                }
                if (delta.removedCount > 0) {
                    WarningText(pluralStringResource(R.plurals.lazarus_warn_unmute, delta.removedCount, delta.removedCount))
                }
            }
            LazarusWarning.STALE_RELAYS -> WarningText(stringResource(R.string.lazarus_warn_stale_relays), error = true)
            LazarusWarning.AFFECTS_OTHERS -> WarningText(stringResource(R.string.lazarus_warn_affects_others))
        }
    }
}

/**
 * The intent question for meaningful-empty kinds (kind 10044): both ends'
 * meaning, the restored version's keys, and an explicit confirmation that
 * starts unchecked. Items are the `n` tags NIP-4e lists encryption keys in.
 */
@Composable
private fun IntentQuestion(
    candidate: LazarusCandidate,
    current: LazarusCandidate?,
    confirmed: Boolean,
    onConfirm: (Boolean) -> Unit
) {
    val chosenKeys = candidate.itemCount.count
    val currentKeys = current?.itemCount?.count ?: 0
    Text(
        if (chosenKeys == 0) stringResource(R.string.lazarus_intent_chosen_empty)
        else pluralStringResource(R.plurals.lazarus_intent_chosen_keys, chosenKeys, chosenKeys),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        if (currentKeys == 0) stringResource(R.string.lazarus_intent_current_empty)
        else pluralStringResource(R.plurals.lazarus_intent_current_keys, currentKeys, currentKeys),
        style = MaterialTheme.typography.bodySmall
    )
    val keys = candidate.event.tags.filter { it.firstOrNull() == "n" && it.size >= 2 }
    if (keys.isNotEmpty()) TagList(stringResource(R.string.lazarus_intent_keys_title), keys)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = confirmed, onCheckedChange = onConfirm)
        Text(stringResource(R.string.lazarus_intent_confirm), style = MaterialTheme.typography.bodySmall)
    }
}

/** What the last restore attempt did, when it didn't publish. */
@Composable
private fun RestoreOutcome(state: LazarusRestoreState) {
    when (state) {
        LazarusRestoreState.Changed -> WarningText(stringResource(R.string.lazarus_changed_notice))
        LazarusRestoreState.ChangedLocally -> WarningText(stringResource(R.string.lazarus_changed_locally), error = true)
        is LazarusRestoreState.Unconfirmed -> WarningText(stringResource(R.string.lazarus_unconfirmed_restore), error = true)
        is LazarusRestoreState.NotAccepted -> {
            val report = state.report
            if (report.writeRelays.isEmpty()) {
                WarningText(stringResource(R.string.lazarus_no_write_relays), error = true)
            } else {
                WarningText(stringResource(R.string.lazarus_not_accepted), error = true)
                WarningText(
                    stringResource(R.string.lazarus_not_accepted_by, relayNames(report.notAccepted.map { rejection ->
                        rejection.relayUrl + (rejection.message?.let { " ($it)" } ?: "")
                    }, report.writeRelaysAreDefaults)),
                    error = true
                )
            }
        }
        LazarusRestoreState.WrongAccount -> WarningText(stringResource(R.string.lazarus_wrong_account), error = true)
        LazarusRestoreState.SignFailed -> WarningText(stringResource(R.string.lazarus_sign_failed), error = true)
        LazarusRestoreState.SignerMismatch -> WarningText(stringResource(R.string.lazarus_signer_mismatch), error = true)
        LazarusRestoreState.Failed -> WarningText(stringResource(R.string.lazarus_restore_failed), error = true)
        else -> Unit
    }
}

/** Relays for a sentence, labeled as defaults when the defaults stand in as write relays. */
@Composable
private fun relayNames(urls: List<String>, defaults: Boolean): String {
    val context = LocalContext.current
    val names = urls.map { host(it) }
    return if (defaults) names.joinToString(", ") { context.getString(R.string.lazarus_default_relay, it) }
    else names.joinToString(", ")
}

/** The published restore, relay by relay: which write relays accepted it and which didn't. */
@Composable
private fun PublishedCard(
    viewModel: LazarusViewModel,
    report: LazarusPublishReport,
    localUpdated: Boolean,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.lazarus_restored_kind, kindLabelForReport(report)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(R.string.lazarus_restored_body, report.accepted.size, report.writeRelays.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (report.judgedOnRestoredList) {
                Text(
                    stringResource(R.string.lazarus_restored_judged_on_restored),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                stringResource(R.string.lazarus_accepted_by, relayNames(report.accepted, report.writeRelaysAreDefaults)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (report.notAccepted.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.lazarus_not_accepted_by,
                        relayNames(report.notAccepted.map { it.relayUrl }, report.writeRelaysAreDefaults)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (report.bestEffort.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.lazarus_best_effort, report.bestEffort.size, report.bestEffort.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                stringResource(if (localUpdated) R.string.lazarus_local_updated else R.string.lazarus_local_not_updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row {
                TextButton(
                    onClick = {
                        onDismiss()
                        viewModel.scan()
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) { Text(stringResource(R.string.lazarus_scan_again)) }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        }
    }
}

/** The restored kind's display name, lowercased for the sentence. */
private fun kindLabelForReport(report: LazarusPublishReport): String =
    when (report.event.kind) {
        3 -> "follow list"
        10000 -> "mute list"
        0 -> "profile"
        10003 -> "bookmarks"
        10044 -> "encryption keys"
        10002 -> "relay list"
        10050 -> "DM relays"
        10006 -> "blocked relays"
        else -> "list"
    }
