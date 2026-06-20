package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.api.ZapCookingApiException
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.repo.RecipePublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sous Chef — AI recipe import (concern 2.1). v1 is **URL import, preview
 * only**: POST the URL to the free, anon `/api/extract-recipe/public`, map the
 * structured response to a [RecipeParser.Recipe], and show a read-only
 * preview. Saving (publish to the user's account) is deferred to concern 2.2 —
 * this mirrors the web's anon "preview until sign-in" path exactly.
 */
class SousChefViewModel : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Preview(val recipe: RecipeParser.Recipe) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Save (publish) overlay state, distinct from the import [state]. */
    sealed interface SaveState {
        data object Idle : SaveState
        data object Saving : SaveState
        data class Saved(val author: String, val dTag: String) : SaveState
        data class Error(val message: String) : SaveState
    }

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState

    fun import(rawUrl: String, api: ZapCookingApi) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = try {
                val resp = api.extractRecipeFromUrl(url)
                val recipe = resp.recipe
                if (resp.success && recipe != null) {
                    State.Preview(recipe.toRecipePreview())
                } else {
                    State.Error(resp.error ?: "Couldn't import a recipe from that link.")
                }
            } catch (e: ZapCookingApiException) {
                State.Error(
                    when (e.code) {
                        429 -> "Too many imports right now — try again in a bit."
                        400 -> api.parseError(e.body) ?: "Couldn't read a recipe from that link."
                        else -> "Import failed (${e.code})."
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow cancellation (e.g. leaving the screen)
            } catch (e: Exception) {
                State.Error("Network error — check your connection and try again.")
            }
        }
    }

    /**
     * Publish the previewed recipe to the user's account (Sous Chef Save).
     * Categories come from the imported recipe's tags (mapped into
     * `recipe.hashtags` by `toRecipePreview`). Requires a signing key.
     */
    fun save(publisher: RecipePublisher, signer: NostrSigner?, clientTagEnabled: Boolean) {
        val preview = _state.value as? State.Preview ?: return
        if (_saveState.value == SaveState.Saving) return
        if (signer == null) {
            _saveState.value = SaveState.Error("Sign in to save recipes.")
            return
        }
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            _saveState.value = when (
                val r = publisher.publish(
                    recipe = preview.recipe,
                    categories = preview.recipe.hashtags,
                    signer = signer,
                    includeClientTag = clientTagEnabled,
                )
            ) {
                is RecipePublisher.Result.Published -> SaveState.Saved(r.author, r.dTag)
                is RecipePublisher.Result.Error -> SaveState.Error(r.message)
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
        _saveState.value = SaveState.Idle
    }
}
