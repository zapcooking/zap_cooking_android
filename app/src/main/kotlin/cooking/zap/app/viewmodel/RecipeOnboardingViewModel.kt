package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the onboarding "Save a few recipes to your Cookbook" step.
 *
 * Sources featured recipes via [RecipeRepository] (the newest recipe window),
 * then curates a small, image-first, author-diverse set biased toward the web's
 * STATIC_COLLECTIONS categories (Breakfast / Dessert / Quick / Italian / Mexican).
 *
 * NOTE: the web also weights "discover" by engagement (`1 + log(zaps+1) + log(likes+1)`).
 * That needs per-recipe zap/like counts which RecipeRepository does not collect, so
 * this approximates the intent with collection-priority + image-preference + author
 * diversity. Wiring real engagement scoring is a follow-up.
 */
class RecipeOnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _featured = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val featured: StateFlow<List<RecipeParser.Recipe>> = _featured

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private var started = false

    fun load(recipeRepo: RecipeRepository) {
        if (started) return
        started = true
        recipeRepo.loadFeed(limit = 100)
        viewModelScope.launch {
            recipeRepo.recipes.collect { recipes ->
                // Commit a single stable selection once a reasonable pool has
                // arrived, so the cards don't reshuffle as results stream in.
                if (_featured.value.isNotEmpty()) return@collect
                if (recipes.size >= POOL_TARGET) {
                    _featured.value = pickFeatured(recipes)
                    _loading.value = false
                }
            }
        }
        // Fallback: commit whatever we have (possibly a thin pool) after a grace
        // window so the step never hangs on a spinner.
        viewModelScope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (_featured.value.isEmpty()) {
                _featured.value = pickFeatured(recipeRepo.recipes.value)
            }
            _loading.value = false
        }
    }

    fun reset() {
        started = false
        _featured.value = emptyList()
        _loading.value = true
    }

    /**
     * Image-first, author-diverse selection biased toward the STATIC_COLLECTIONS
     * categories. Recipes in a featured collection come first (shuffled), then the
     * rest (shuffled); capped at [MAX_PER_AUTHOR] per author and [TARGET] total.
     */
    private fun pickFeatured(recipes: List<RecipeParser.Recipe>): List<RecipeParser.Recipe> {
        if (recipes.isEmpty()) return emptyList()
        val withImage = recipes.filter { !it.image.isNullOrBlank() }
        val pool = withImage.ifEmpty { recipes }
        fun inCollection(r: RecipeParser.Recipe) = r.categories.any { it.lowercase() in COLLECTION_TAGS }
        val ordered = pool.filter(::inCollection).shuffled() + pool.filterNot(::inCollection).shuffled()

        val perAuthor = HashMap<String, Int>()
        val featured = mutableListOf<RecipeParser.Recipe>()
        for (r in ordered) {
            val count = perAuthor[r.author] ?: 0
            if (count >= MAX_PER_AUTHOR) continue
            perAuthor[r.author] = count + 1
            featured.add(r)
            if (featured.size >= TARGET) break
        }
        return featured
    }

    companion object {
        /** Mirrors the web's STATIC_COLLECTIONS categories (src/lib/exploreUtils.ts). */
        private val COLLECTION_TAGS = setOf("breakfast", "dessert", "quick", "italian", "mexican")
        private const val TARGET = 9
        private const val MAX_PER_AUTHOR = 2
        private const val POOL_TARGET = 40
        private const val LOAD_TIMEOUT_MS = 8_000L
    }
}
