package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.lazarus.LazarusCandidate
import cooking.zap.app.lazarus.LazarusDelta
import cooking.zap.app.lazarus.LazarusKindProfile
import cooking.zap.app.lazarus.LazarusPublisher
import cooking.zap.app.lazarus.LazarusScanEngine
import cooking.zap.app.lazarus.LazarusScanResult
import cooking.zap.app.lazarus.LatestRead
import cooking.zap.app.lazarus.applyLazarusPrivateTags
import cooking.zap.app.lazarus.computeLazarusDelta
import cooking.zap.app.lazarus.getLazarusKindProfile
import cooking.zap.app.lazarus.getLazarusKindProfiles
import cooking.zap.app.lazarus.scanLazarusKind
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.repo.RelayListRepository
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State for one kind's recovery flow: idle → scanning → result, plus the
 * private-item decryption that must land BEFORE a restore can be approved.
 */
sealed class LazarusUiState {
    object Idle : LazarusUiState()
    data class Scanning(val kind: Int) : LazarusUiState()
    data class Done(val scan: LazarusScanResult) : LazarusUiState()
    data class Failed(val message: String) : LazarusUiState()
}

sealed class LazarusRestoreState {
    object Idle : LazarusRestoreState()
    data class Working(val kind: Int) : LazarusRestoreState()
    data class Published(
        val acceptedRelays: Int,
        val unconfirmedRelays: Int,
        val bestEffortRelays: Int
    ) : LazarusRestoreState()

    /** The live version differs from the reviewed one — recompute and re-ask. */
    data class Changed(val scan: LazarusScanResult) : LazarusRestoreState()
    data class WrongAccount(val signedBy: String) : LazarusRestoreState()
    data class Failed(val message: String) : LazarusRestoreState()
}

class LazarusViewModel(app: Application) : AndroidViewModel(app) {

    val kinds: List<LazarusKindProfile> = getLazarusKindProfiles()

    private var engine: LazarusScanEngine? = null
    private var publisher: LazarusPublisher? = null
    private var relayPool: RelayPool? = null
    private var relayListRepo: RelayListRepository? = null

    private val _selectedKind = MutableStateFlow<LazarusKindProfile?>(null)
    val selectedKind: StateFlow<LazarusKindProfile?> = _selectedKind

    private val _uiState = MutableStateFlow<LazarusUiState>(LazarusUiState.Idle)
    val uiState: StateFlow<LazarusUiState> = _uiState

    /** Decrypted private items keyed by event id, applied to the ranking. */
    private val _privateTags = MutableStateFlow<Map<String, List<List<String>>>>(emptyMap())

    /** Event ids whose encrypted content could NOT be decrypted: a restore of
     *  these stays disabled (the private delta is unknowable). */
    private val _undecryptable = MutableStateFlow<Set<String>>(emptySet())
    val undecryptable: StateFlow<Set<String>> = _undecryptable

    private val _selectedCandidate = MutableStateFlow<LazarusCandidate?>(null)
    val selectedCandidate: StateFlow<LazarusCandidate?> = _selectedCandidate

    private val _delta = MutableStateFlow<LazarusDelta?>(null)
    val delta: StateFlow<LazarusDelta?> = _delta

    private val _restoreState = MutableStateFlow<LazarusRestoreState>(LazarusRestoreState.Idle)
    val restoreState: StateFlow<LazarusRestoreState> = _restoreState

    fun init(relayPool: RelayPool, signer: NostrSigner?, relayListRepo: RelayListRepository) {
        this.relayPool = relayPool
        this.relayListRepo = relayListRepo
        currentSigner = signer
        // A screen revisit after closeEngine() rebuilds the engine.
        if (engine == null) {
            val e = LazarusScanEngine(signer)
            engine = e
            publisher = LazarusPublisher(e)
        }
    }

    override fun onCleared() {
        // Upstream finding 7: teardown closes every scan socket.
        engine?.close()
        super.onCleared()
    }

    fun selectKind(profile: LazarusKindProfile, pubkey: String) {
        _selectedKind.value = profile
        _selectedCandidate.value = null
        _delta.value = null
        _restoreState.value = LazarusRestoreState.Idle
        _privateTags.value = emptyMap()
        _undecryptable.value = emptySet()
        scan(profile, pubkey)
    }

    fun backToKinds() {
        _selectedKind.value = null
        _uiState.value = LazarusUiState.Idle
        _selectedCandidate.value = null
        _delta.value = null
    }

    private fun scan(profile: LazarusKindProfile, pubkey: String) {
        val engine = engine ?: return
        _uiState.value = LazarusUiState.Scanning(profile.kind)
        viewModelScope.launch {
            try {
                val (read, write) = userRelays(pubkey)
                engine.scanRelayUrls = engine.scanRelays(
                    userRead = read,
                    userWrite = write,
                    appRelays = relayPool?.getWriteRelayUrls() ?: emptyList()
                )
                val result = scanLazarusKind(profile.kind, pubkey, engine)
                _uiState.value = LazarusUiState.Done(result)
                decryptPrivateItems(profile, pubkey, result)
            } catch (e: Exception) {
                _uiState.value = LazarusUiState.Failed(e.message ?: "Scan failed")
            }
        }
    }

    fun loadOlder() {
        val profile = _selectedKind.value ?: return
        val done = _uiState.value as? LazarusUiState.Done ?: return
        val engine = engine ?: return
        val signerPubkey = done.scan.candidates.firstOrNull()?.event?.pubkey ?: return
        viewModelScope.launch {
            try {
                val (read, write) = userRelays(signerPubkey)
                engine.scanRelayUrls = engine.scanRelays(read, write, relayPool?.getWriteRelayUrls() ?: emptyList())
                val merged = cooking.zap.app.lazarus.loadOlderLazarusVersions(
                    profile, done.scan, signerPubkey, engine, _privateTags.value
                )
                _uiState.value = LazarusUiState.Done(merged)
            } catch (e: Exception) {
                _uiState.value = LazarusUiState.Failed(e.message ?: "Couldn't load older versions")
            }
        }
    }

    /**
     * Decrypt encrypted candidates' private items (to self) and re-rank, so
     * the ranking AND the delta review see the full list. Undecryptable
     * candidates are recorded — restoring one stays disabled until its
     * private delta is known (upstream finding 4, fail-closed variant).
     */
    private suspend fun decryptPrivateItems(
        profile: LazarusKindProfile,
        pubkey: String,
        result: LazarusScanResult
    ) {
        val signer = currentSigner ?: return
        val decrypted = _privateTags.value.toMutableMap()
        val failed = _undecryptable.value.toMutableSet()
        var processed = 0
        for (candidate in result.candidates) {
            if (cooking.zap.app.lazarus.getContentEncryption(candidate.event.content) == null) continue
            if (candidate.event.id in decrypted || candidate.event.id in failed) continue
            if (processed >= DECRYPT_CAP) break // cap signer round trips; the rest stay estimated
            processed++
            try {
                val plain = signer.nip44Decrypt(candidate.event.content, pubkey)
                val tags = cooking.zap.app.lazarus.parsePrivateTags(plain)
                if (tags != null) decrypted[candidate.event.id] = tags else failed.add(candidate.event.id)
            } catch (_: Exception) {
                failed.add(candidate.event.id)
            }
        }
        _privateTags.value = decrypted
        _undecryptable.value = failed
        val current = _uiState.value as? LazarusUiState.Done ?: return
        _uiState.value = LazarusUiState.Done(
            applyLazarusPrivateTags(profile, current.scan, decrypted)
        )
        // Refresh the open delta: its privateUnknown flag may have resolved.
        _selectedCandidate.value?.let { recomputeDelta(it) }
    }

    fun selectCandidate(candidate: LazarusCandidate?) {
        _selectedCandidate.value = candidate
        recomputeDelta(candidate)
    }

    private fun recomputeDelta(candidate: LazarusCandidate?) {
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan
        _delta.value = if (candidate == null || scan == null) null
        else computeLazarusDelta(candidate.event, scan.current?.event, _privateTags.value)
    }

    /** True when the restore CTA may be enabled for this candidate. */
    fun canRestore(candidate: LazarusCandidate): Boolean {
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return false
        if (scan.requiresIntentConfirmation && candidate.event.id == scan.current?.event?.id) return false
        // Undecryptable private content = unknowable delta = no restore.
        if (candidate.event.id in _undecryptable.value) return false
        if (_restoreState.value is LazarusRestoreState.Working) return false
        return true
    }

    fun restore(chosen: LazarusCandidate, signer: NostrSigner, pubkey: String) {
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return
        val profile = _selectedKind.value ?: return
        val publisher = publisher ?: return
        _restoreState.value = LazarusRestoreState.Working(profile.kind)
        viewModelScope.launch {
            val (_, write) = userRelays(pubkey)
            val result = publisher.publish(
                chosen = chosen.event,
                reviewedCurrent = scan.current?.event,
                pubkey = pubkey,
                signer = signer,
                writeRelays = write.ifEmpty { relayPool?.getWriteRelayUrls() ?: emptyList() },
                respondingScanRelays = scan.respondingRelays
            )
            _restoreState.value = when (result) {
                is LazarusPublisher.Result.Published -> LazarusRestoreState.Published(
                    acceptedRelays = result.acceptedWriteRelays.size,
                    unconfirmedRelays = result.unconfirmedWriteRelays.size,
                    bestEffortRelays = result.bestEffortRelays.size
                )
                is LazarusPublisher.Result.Changed -> {
                    // Re-scan so the review reflects the newer live version.
                    scan(profile, pubkey)
                    LazarusRestoreState.Changed(scan)
                }
                is LazarusPublisher.Result.WrongAccount ->
                    LazarusRestoreState.WrongAccount(result.signedBy)
                is LazarusPublisher.Result.Failed ->
                    LazarusRestoreState.Failed(result.reason)
            }
        }
    }

    fun dismissRestoreState() {
        if (_restoreState.value !is LazarusRestoreState.Working) {
            _restoreState.value = LazarusRestoreState.Idle
        }
    }

    private suspend fun userRelays(pubkey: String): Pair<List<String>, List<String>> {
        val repo = relayListRepo ?: return emptyList<String>() to emptyList()
        val read = runCatching { repo.getReadRelays(pubkey) }.getOrNull().orEmpty()
        val write = runCatching { repo.getWriteRelays(pubkey) }.getOrNull().orEmpty()
        return read to write
    }

    private var currentSigner: NostrSigner? = null

    fun setSigner(signer: NostrSigner?) {
        currentSigner = signer
    }

    /** Screen left: disconnect every scan socket (upstream finding 7). */
    fun closeEngine() {
        engine?.close()
        engine = null
        publisher = null
    }

    companion object {
        /** Upper bound on signer decryption round trips per scan. */
        const val DECRYPT_CAP = 20
    }
}
