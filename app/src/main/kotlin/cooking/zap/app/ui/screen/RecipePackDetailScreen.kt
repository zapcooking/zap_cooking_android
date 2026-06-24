package cooking.zap.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cooking.zap.app.R
import cooking.zap.app.nostr.toNpub
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.RecipePackSummary
import cooking.zap.app.ui.component.ProfilePicture
import cooking.zap.app.ui.component.RecipeCard
import cooking.zap.app.ui.component.RecipePosterSkeleton
import cooking.zap.app.viewmodel.RecipePackDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipePackDetailScreen(
    viewModel: RecipePackDetailViewModel,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    onRecipeClick: (author: String, dTag: String) -> Unit,
) {
    val pack by viewModel.pack.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val failedCount by viewModel.failedCount.collectAsState()
    val notFound by viewModel.notFound.collectAsState()
    val profileVersion by eventRepo.profileVersion.collectAsState()

    val creatorProfile = remember(pack?.author, profileVersion) {
        pack?.author?.let { eventRepo.getProfileData(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = pack?.title ?: stringResource(R.string.tab_packs),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            )
        }
    ) { padding ->
        when {
            notFound -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_recipe_pack_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            pack == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                val columns = GridCells.Adaptive(minSize = 160.dp)
                val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                val spacing = Arrangement.spacedBy(12.dp)
                val currentPack = pack ?: return@Scaffold

                LazyVerticalGrid(
                    columns = columns,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = contentPadding,
                    horizontalArrangement = spacing,
                    verticalArrangement = spacing,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PackHeader(
                            pack = currentPack,
                            creatorName = creatorProfile?.displayString
                                ?: currentPack.author.toNpub().let { "${it.take(12)}...${it.takeLast(4)}" },
                            creatorPicture = creatorProfile?.picture,
                        )
                    }

                    if (failedCount > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.error_recipe_pack_missing_members, failedCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }

                    items(recipes, key = { it.id }) { recipe ->
                        RecipeCard(
                            recipe = recipe,
                            onClick = { onRecipeClick(recipe.author, recipe.dTag) },
                        )
                    }

                    items(pendingCount) {
                        RecipePosterSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackHeader(
    pack: RecipePackSummary,
    creatorName: String,
    creatorPicture: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            if (pack.image != null) {
                AsyncImage(
                    model = pack.image,
                    contentDescription = pack.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restaurant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = pack.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (pack.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = pack.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfilePicture(url = creatorPicture, size = 24)
            Text(
                text = creatorName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    id = R.plurals.recipe_count_label,
                    count = pack.recipeCount,
                    pack.recipeCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
