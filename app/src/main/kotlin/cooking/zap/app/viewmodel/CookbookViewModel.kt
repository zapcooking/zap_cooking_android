package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.repo.CookbookCovers
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the **Cookbook → Saved** sub-tab (A14 PR 3b-i). It does not own the
 * recipe-list data — those live in [RecipeBookmarkRepository] (PR 3a), already
 * loaded at login — it just re-exposes them and lazily resolves a cover image
 * per list via [CookbookCovers] (which reuses the existing recipe lookups).
 *
 * Read-only: no writes here. List management (rename/delete/cover) is PR 3b-iii.
 */
class CookbookViewModel : ViewModel() {
    private val _lists = MutableStateFlow<List<CookbookList>>(emptyList())
    /** The user's recipe collections — default Saved list first (from PR 3a). */
    val lists: StateFlow<List<CookbookList>> = _lists

    private val _covers = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** Resolved cover URL per list `d`-tag; absent until resolved, null when none resolves. */
    val covers: StateFlow<Map<String, String?>> = _covers

    private var bound = false

    /**
     * `d`-tags with an in-flight cover resolution. Guards against a fresh `lists`
     * emission launching a duplicate coroutine (and duplicate relay fetch) for a
     * cover that's still resolving. Only touched on the viewModelScope (main)
     * dispatcher, so a plain set is safe.
     */
    private val resolvingCovers = mutableSetOf<String>()

    /**
     * Mirror [bookmarkRepo]'s lists into [lists] and resolve covers as they
     * arrive. Idempotent — safe to call from a `LaunchedEffect` on every entry.
     */
    fun bind(bookmarkRepo: RecipeBookmarkRepository, recipeRepo: RecipeRepository) {
        if (bound) return
        bound = true
        viewModelScope.launch {
            bookmarkRepo.lists.collect { lists ->
                _lists.value = lists
                lists.forEach { list -> resolveCover(list, recipeRepo) }
            }
        }
    }

    /**
     * Resolve [list]'s cover. Skips if a URL already resolved, or if a resolution
     * for this `d`-tag is already in-flight (so a re-emission of `lists` can't
     * launch a duplicate coroutine / relay fetch). A prior null result is allowed
     * to re-attempt — a later relay-fill of the cover recipe can then surface it.
     */
    private fun resolveCover(list: CookbookList, recipeRepo: RecipeRepository) {
        val dTag = list.dTag
        if (_covers.value[dTag] != null) return
        if (!resolvingCovers.add(dTag)) return
        viewModelScope.launch {
            try {
                val url = CookbookCovers.resolve(list, recipeRepo)
                _covers.value = _covers.value + (dTag to url)
            } finally {
                resolvingCovers.remove(dTag)
            }
        }
    }
}
