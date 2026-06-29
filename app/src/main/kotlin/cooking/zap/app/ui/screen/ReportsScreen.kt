package cooking.zap.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.Nip56
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.ProfilePicture

/**
 * Moderation inbox — a read-only list of NIP-56 reports routed to the current account. Newest-first,
 * with loading / empty / error states. Tapping a report opens the reported user in their group
 * (member list → existing kick/ban); the "view reported message" link jumps to the message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: cooking.zap.app.viewmodel.ReportsViewModel,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    groupNameFor: (groupId: String) -> String? = { null },
    onOpenReportedUser: (groupId: String?, reportedPubkey: String) -> Unit,
    onOpenReportedMessage: (groupId: String, eventId: String) -> Unit,
) {
    val reports by viewModel.reports.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    // Bumps when profile metadata arrives; keying row remembers on it refreshes names/avatars
    // that initially fell back to npub/no-picture before the queued fetches completed.
    val profileVersion by eventRepo.profileVersion.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_reports)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                reports.isEmpty() && loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                reports.isEmpty() && error != null -> {
                    EmptyState(
                        title = stringResource(R.string.reports_error),
                        hint = error,
                    )
                }
                reports.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.reports_empty),
                        hint = stringResource(R.string.reports_empty_hint),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = reports, key = { it.id }) { report ->
                            ReportRow(
                                report = report,
                                eventRepo = eventRepo,
                                profileVersion = profileVersion,
                                groupName = report.groupId?.let(groupNameFor),
                                onOpenUser = { onOpenReportedUser(report.groupId, report.reportedPubkey) },
                                onOpenMessage = {
                                    val gid = report.groupId
                                    val eid = report.reportedEventId
                                    if (gid != null && eid != null) onOpenReportedMessage(gid, eid)
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, hint: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportRow(
    report: Nip56.ReportInfo,
    eventRepo: EventRepository,
    profileVersion: Int,
    groupName: String?,
    onOpenUser: () -> Unit,
    onOpenMessage: () -> Unit,
) {
    val reporterName = remember(report.reporterPubkey, profileVersion) { displayName(eventRepo, report.reporterPubkey) }
    val reportedName = remember(report.reportedPubkey, profileVersion) { displayName(eventRepo, report.reportedPubkey) }
    val reportedPic = remember(report.reportedPubkey, profileVersion) { eventRepo.getProfileData(report.reportedPubkey)?.picture }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUser() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header: reporter + relative time
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.reports_reported_by, reporterName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTime(report.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Reported user + category
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ProfilePicture(url = reportedPic, size = 36)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reportedName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (report.categoryLabel.isNotBlank()) {
                    CategoryChip(report.categoryLabel)
                }
            }
        }
        if (report.reason.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = report.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // Footer: group + link to reported message
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val groupLabel = groupName ?: report.groupId
            if (!groupLabel.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.reports_in_group, groupLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (report.reportedEventId != null && report.groupId != null) {
                TextButton(onClick = onOpenMessage) {
                    Text(stringResource(R.string.reports_view_message))
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun displayName(eventRepo: EventRepository, pubkey: String): String =
    eventRepo.getProfileData(pubkey)?.displayString
        ?: pubkey.toNpub().let { "${it.take(12)}…${it.takeLast(4)}" }

private fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val nowSec = System.currentTimeMillis() / 1000
    val diff = (nowSec - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86_400 -> "${diff / 3600}h"
        diff < 604_800 -> "${diff / 86_400}d"
        else -> "${diff / 604_800}w"
    }
}
