package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.PackRecipeCoordinate
import cooking.zap.app.repo.RecipePackRepository
import cooking.zap.app.repo.RecipePackSummary
import cooking.zap.app.repo.RecipeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecipePackDetailViewModel : ViewModel() {
    private val _pack = MutableStateFlow<RecipePackSummary?>(null)
    val pack: StateFlow<RecipePackSummary?> = _pack

    private val _recipes = MutableStateFlow<List<RecipeParser.Recipe>>(emptyList())
    val recipes: StateFlow<List<RecipeParser.Recipe>> = _recipes

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _failedCount = MutableStateFlow(0)
    val failedCount: StateFlow<Int> = _failedCount

    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound

    private var loadedKey: String? = null
    private var loadJob: Job? = null

    fun load(
        author: String,
        dTag: String,
        packRepo: RecipePackRepository,
        recipeRepo: RecipeRepository,
    ) {
        val normalizedAuthor = author.trim()
        val normalizedDTag = dTag.trim()
        val key = "$normalizedAuthor:$normalizedDTag"
        if (loadedKey == key && (_pack.value != null || _notFound.value)) return
        loadedKey = key

        loadJob?.cancel()
        _pack.value = null
        _recipes.value = emptyList()
        _pendingCount.value = 0
        _failedCount.value = 0
        _notFound.value = false
        _isLoading.value = true

        loadJob = viewModelScope.launch {
            val packEvent = packRepo.requestPackEvent(normalizedAuthor, normalizedDTag)
            if (loadedKey != key) return@launch
            val summary = packEvent?.let { packRepo.parseSummary(it) }
            if (summary == null || packEvent == null) {
                _notFound.value = true
                _isLoading.value = false
                return@launch
            }

            _pack.value = summary
            val coordinates = packRepo.extractRecipeCoordinates(packEvent)
            val orderedKeys = coordinates.map(::coordinateKey)
            _pendingCount.value = orderedKeys.size

            val resolvedByKey = LinkedHashMap<String, RecipeParser.Recipe>()
            fun emitResolved() {
                _recipes.value = orderedKeys.mapNotNull { resolvedByKey[it] }
                _pendingCount.value = (orderedKeys.size - resolvedByKey.size - _failedCount.value).coerceAtLeast(0)
            }

            // Cache-first (event cache + ObjectBox), then network fill missing coordinates.
            for (coord in coordinates) {
                if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return@launch
                val event = recipeRepo.findRecipeEventByCoordinate(coord.kind, coord.author, coord.dTag)
                val parsed = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
                if (parsed != null) {
                    resolvedByKey[coordinateKey(coord)] = parsed
                }
            }
            if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return@launch
            emitResolved()

            val unresolved = coordinates.filter { coordinateKey(it) !in resolvedByKey }
            for (coord in unresolved) {
                if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return@launch
                val event = recipeRepo.requestRecipeEventByCoordinate(coord.kind, coord.author, coord.dTag)
                val parsed = event?.let { RecipeFormats.forEvent(it)?.parse(it) }
                if (parsed != null) {
                    resolvedByKey[coordinateKey(coord)] = parsed
                } else {
                    _failedCount.value = _failedCount.value + 1
                }
                if (loadedKey != key || !kotlin.coroutines.coroutineContext.isActive) return@launch
                emitResolved()
            }

            _isLoading.value = false
        }
    }

    private fun coordinateKey(coord: PackRecipeCoordinate): String =
        "${coord.kind}:${coord.author}:${coord.dTag}"
}
