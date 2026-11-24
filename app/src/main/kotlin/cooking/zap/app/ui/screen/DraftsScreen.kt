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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.nostr.Nip05
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.nostr.Nip30
import cooking.zap.app.nostr.Nip37
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.ui.component.RichContent
import cooking.zap.app.ui.component.parseImetaTags
import cooking.zap.app.R
import cooking.zap.app.api.ScheduleStatus
import cooking.zap.app.api.ScheduledItem
import cooking.zap.app.viewmodel.DraftsViewModel
import cooking.zap.app.viewmodel.scheduledPreview
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    viewModel: DraftsViewModel,
    onBack: () -> Unit,
    onDraftClick: (Nip37.Draft) -> Unit,
    onDeleteDraft: (String) -> Unit,
    onCancelScheduled: (String) -> Unit,
    onRefreshScheduled: () -> Unit,
    userProfile: ProfileData? = null,
    initialTab: Int = 0
) {
    val drafts by viewModel.drafts.collectAsState()
    val scheduledPosts by viewModel.scheduledPosts.collectAsState()
    val scheduledLoading by viewModel.scheduledLoading.collectAsState()
    val scheduledRefreshing by viewModel.scheduledRefreshing.collectAsState()
    val scheduledError by viewModel.scheduledError.collectAsState()
    val cancellingIds by viewModel.cancellingIds.collectAsState()

    // What happened to a cancel: cancelled, already published, still scheduled…
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.scheduledMessages.collect { message ->
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }

    var selectedTab by remember { mutableIntStateOf(initialTab) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drafts_scheduled_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.drafts_tab)) },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.scheduled_tab)) },
                    icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            when (selectedTab) {
                0 -> DraftsTab(
                    drafts = drafts,
                    userProfile = userProfile,
                    onDraftClick = onDraftClick,
                    onDeleteDraft = onDeleteDraft
                )
                1 -> ScheduledTab(
                    posts = scheduledPosts,
                    loading = scheduledLoading,
                    refreshing = scheduledRefreshing,
                    error = scheduledError,
                    cancellingIds = cancellingIds,
                    userProfile = userProfile,
                    onCancel = onCancelScheduled,
                    onRefresh = onRefreshScheduled
                )
            }
        }
    }
}

@Composable
private fun DraftsTab(
    drafts: List<Nip37.Draft>,
    userProfile: ProfileData?,
    onDraftClick: (Nip37.Draft) -> Unit,
    onDeleteDraft: (String) -> Unit
) {
    if (drafts.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
            message = stringResource(R.string.no_drafts_saved)
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = drafts, key = { it.dTag }) { draft ->
                DraftItem(
                    draft = draft,
                    userProfile = userProfile,
                    onClick = { onDraftClick(draft) },
                    onDelete = { onDeleteDraft(draft.dTag) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTab(
    posts: List<ScheduledItem>,
    loading: Boolean,
    refreshing: Boolean,
    @StringRes error: Int?,
    cancellingIds: Set<String>,
    userProfile: ProfileData?,
    onCancel: (String) -> Unit,
    onRefresh: () -> Unit
) {
    if (loading && posts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // One scrollable list in every state, so pull to refresh works when it is
    // empty or showing an error too.
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (error != null) {
                item(key = "error") {
                    ScheduledLoadError(message = stringResource(error))
                }
            }
            if (posts.isEmpty() && error == null) {
                item(key = "empty") {
                    Box(modifier = Modifier.fillParentMaxSize()) {
                        EmptyState(
                            icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                            message = stringResource(R.string.no_scheduled_posts)
                        )
                    }
                }
            }
            items(items = posts, key = { it.id }) { post ->
                ScheduledPostItem(
                    item = post,
                    userProfile = userProfile,
                    cancelling = post.id in cancellingIds,
                    onCancel = { onCancel(post.id) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
        }
    }
}

/** The list would not load. Rows from an earlier load, if any, stay below it. */
@Composable
private fun ScheduledLoadError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.scheduled_pull_to_retry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun DraftItem(
    draft: Nip37.Draft,
    userProfile: ProfileData?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isReply = draft.tags.any { it.size >= 2 && it[0] == "e" }
    val displayName = userProfile?.displayString ?: stringResource(R.string.draft_label)
    val emojiMap = remember(draft.dTag) { Nip30.parseEmojiTags(draft.tags) }
    val imetaMap = remember(draft.dTag) { parseImetaTags(draft.tags) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header row — mirrors PostCard
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(url = userProfile?.picture)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!userProfile?.nip05.isNullOrBlank()) {
                    Text(
                        text = Nip05.formatForDisplay(userProfile!!.nip05!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isReply) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.reply_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = formatDraftTimestamp(draft.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.delete_draft),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Note content — full RichContent
        RichContent(
            content = draft.content.ifBlank { stringResource(R.string.empty_content) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (draft.content.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            emojiMap = emojiMap,
            imetaMap = imetaMap
        )

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * One scheduled row: what kind of post it is, when it publishes, its status,
 * and a preview — text, first picture, poll options ([scheduledPreview]). A
 * pending row can be cancelled; a failed row says plainly that it will not go
 * out, with the server's own detail underneath.
 */
@Composable
private fun ScheduledPostItem(
    item: ScheduledItem,
    userProfile: ProfileData?,
    cancelling: Boolean,
    onCancel: () -> Unit
) {
    val post = item.event
    val displayName = remember(post.pubkey, userProfile?.displayString) {
        userProfile?.displayString ?: post.pubkey.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" }
    }
    val failed = item.status == ScheduleStatus.FAILED
    val preview = remember(item.id) { scheduledPreview(post) }
    val emojiMap = remember(post.id) { Nip30.parseEmojiTags(post) }
    val imetaMap = remember(post.id) { parseImetaTags(post.tags) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Header row — mirrors PostCard
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(url = userProfile?.picture)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(preview.kind.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            // Status + publish time badge in place of the timestamp
            Surface(
                color = if (failed) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val badgeColor = if (failed) MaterialTheme.colorScheme.onErrorContainer
                                     else MaterialTheme.colorScheme.onPrimaryContainer
                    Icon(
                        if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = badgeColor
                    )
                    Text(
                        text = if (failed) {
                            stringResource(R.string.scheduled_status_failed) + " · " + formatScheduledTime(item.publishAt)
                        } else {
                            formatScheduledTime(item.publishAt)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                }
            }
            // Only a pending post can be cancelled. The row stays until the server answers.
            if (!failed) {
                if (cancelling) {
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.remove_from_schedule),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Text: the note, caption, poll question or article title. Inline images
        // in a note are drawn by RichContent, exactly as in the feed.
        if (preview.text.isNotBlank()) {
            RichContent(
                content = preview.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                emojiMap = emojiMap,
                imetaMap = imetaMap
            )
        } else if (preview.imageUrl == null && preview.pollOptions.isEmpty()) {
            Text(
                text = stringResource(R.string.scheduled_no_caption),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // First picture of a gallery post, or a video's poster frame.
        preview.imageUrl?.let { url ->
            Spacer(Modifier.height(6.dp))
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        if (preview.pollOptions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview.pollOptions.forEach { option ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scheduled_failed_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            // The server's or a relay's own words: shown as detail, never interpreted.
            item.lastError?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = stringResource(R.string.scheduled_failed_detail, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val draftTimestampFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)
private val scheduledSameDayFormat = SimpleDateFormat("h:mm a", Locale.US)
private val scheduledDateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)

private fun formatDraftTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    return draftTimestampFormat.format(Date(epoch * 1000))
}

private fun formatScheduledTime(epoch: Long): String {
    val date = Date(epoch * 1000)
    val now = Date()
    val todayCal = java.util.Calendar.getInstance().apply { time = now }
    val targetCal = java.util.Calendar.getInstance().apply { time = date }
    return if (todayCal.get(java.util.Calendar.DAY_OF_YEAR) == targetCal.get(java.util.Calendar.DAY_OF_YEAR) &&
               todayCal.get(java.util.Calendar.YEAR) == targetCal.get(java.util.Calendar.YEAR)) {
        scheduledSameDayFormat.format(date)
    } else {
        scheduledDateFormat.format(date)
    }
}
