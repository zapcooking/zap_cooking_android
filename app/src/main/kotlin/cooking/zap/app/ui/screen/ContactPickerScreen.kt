package cooking.zap.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cooking.zap.app.R
import cooking.zap.app.nostr.Nip05
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.ui.component.ProfilePicture

/**
 * Single-select contact picker for starting a 1:1 direct message. Tapping a contact immediately
 * opens a one-on-one conversation with that peer — DMs are 1:1 only; there is no group-DM composer.
 * (Existing multi-recipient threads still render read-only via [DmListScreen], but new ones can't
 * be created here.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerScreen(
    eventRepo: EventRepository,
    contactRepo: ContactRepository,
    onBack: () -> Unit,
    onConfirm: (pubkey: String) -> Unit
) {
    val followList by contactRepo.followList.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val filteredList = remember(followList, query) {
        if (query.isBlank()) followList
        else {
            val q = query.trim().lowercase()
            followList.filter { entry ->
                val profile = eventRepo.getProfileData(entry.pubkey)
                profile?.displayString?.lowercase()?.contains(q) == true ||
                profile?.nip05?.lowercase()?.contains(q) == true ||
                entry.pubkey.contains(q)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_conversation)) },
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.nav_search)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = filteredList, key = { it.pubkey }) { entry ->
                val profile = remember(entry.pubkey) { eventRepo.getProfileData(entry.pubkey) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConfirm(entry.pubkey) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    ProfilePicture(url = profile?.picture, size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayString
                                ?: entry.pubkey.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile?.nip05 != null) {
                            Text(
                                text = Nip05.formatForDisplay(profile.nip05),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        } // end Column
    }
}
