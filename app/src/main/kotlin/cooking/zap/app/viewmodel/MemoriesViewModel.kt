package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.repo.MemoriesRepository
import cooking.zap.app.repo.MemoryGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the full Memories ("On this day") screen. Mirrors the web's
 * `/memories` page: cache-first [load], cache-bypassing [refresh] that keeps the
 * current data and surfaces a notice when a refresh can't be confirmed
 * authoritative. Read-only — all the work lives in [MemoriesRepository].
 */
class MemoriesViewModel : ViewModel() {

    private var repo: MemoriesRepository? = null
    private var pubkey: String? = null

    private val _groups = MutableStateFlow<List<MemoryGroup>>(emptyList())
    /** Groups sorted 1→2→3 years ago. */
    val groups: StateFlow<List<MemoryGroup>> = _groups

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _refreshNotice = MutableStateFlow<String?>(null)
    /** Set to the cached-on-failure notice; cleared on a successful refresh/load. */
    val refreshNotice: StateFlow<String?> = _refreshNotice

    private val _loaded = MutableStateFlow(false)
    /** True once a load has completed (so the UI can distinguish "loading" from "empty"). */
    val loaded: StateFlow<Boolean> = _loaded

    /**
     * Idempotent per account. Re-initializing with a DIFFERENT pubkey (an account
     * switch while this screen stays alive) resets the state and reloads for the
     * new account, so it never shows the prior account's memories. Same-account
     * re-calls (recomposition) are a no-op.
     */
    fun init(repo: MemoriesRepository, pubkey: String?) {
        if (this.repo === repo && this.pubkey == pubkey) return
        this.repo = repo
        this.pubkey = pubkey
        // Drop the prior account's data so the switch can't flash stale memories.
        _groups.value = emptyList()
        _loaded.value = false
        _refreshNotice.value = null
        load()
    }

    fun load() {
        val repo = repo ?: return
        val pk = pubkey ?: return
        _loading.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.getMemoriesCached(pk) }
                // Discard if an account switch superseded this load mid-flight.
                if (pk != pubkey) return@launch
                _groups.value = result.sortedBy { it.yearsAgo }
                _loaded.value = true
            } catch (_: Exception) {
                // Never-throw contract: leave whatever we have.
            } finally {
                if (pk == pubkey) _loading.value = false
            }
        }
    }

    fun refresh() {
        val repo = repo ?: return
        val pk = pubkey ?: return
        if (_refreshing.value) return
        _refreshing.value = true
        _refreshNotice.value = null
        viewModelScope.launch {
            try {
                val (fresh, refreshed) = withContext(Dispatchers.IO) { repo.refreshMemories(pk) }
                if (pk != pubkey) return@launch // account switched mid-refresh — discard
                if (refreshed) {
                    _groups.value = fresh.sortedBy { it.yearsAgo }
                    _loaded.value = true
                } else {
                    // Timeout-empty refresh: keep the current (cached) data, explain why.
                    _refreshNotice.value = "Couldn't refresh — showing cached memories."
                }
            } catch (_: Exception) {
                if (pk == pubkey) _refreshNotice.value = "Couldn't refresh — showing cached memories."
            } finally {
                if (pk == pubkey) _refreshing.value = false
            }
        }
    }
}
