package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.lazarus.LazarusCandidate
import cooking.zap.app.lazarus.LazarusDelta
import cooking.zap.app.lazarus.LazarusEncryption
import cooking.zap.app.lazarus.LazarusKindProfile
import cooking.zap.app.lazarus.LazarusListItem
import cooking.zap.app.lazarus.LazarusLocalStores
import cooking.zap.app.lazarus.LazarusProfileChange
import cooking.zap.app.lazarus.LazarusRanking
import cooking.zap.app.lazarus.LazarusRelayOutcome
import cooking.zap.app.lazarus.LazarusPublishReport
import cooking.zap.app.lazarus.LazarusPublisher
import cooking.zap.app.lazarus.LazarusRestoreStage
import cooking.zap.app.lazarus.LazarusScanEngine
import cooking.zap.app.lazarus.LazarusScanFailedException
import cooking.zap.app.lazarus.LazarusScanResult
import cooking.zap.app.lazarus.LazarusSocketTransport
import cooking.zap.app.lazarus.LazarusSortOrder
import cooking.zap.app.lazarus.applyLazarusPrivateTags
import cooking.zap.app.lazarus.computeLazarusDelta
import cooking.zap.app.lazarus.computeLazarusProfileChanges
import cooking.zap.app.lazarus.getContentEncryption
import cooking.zap.app.lazarus.getLazarusKindProfile
import cooking.zap.app.lazarus.getLazarusKindProfiles
import cooking.zap.app.lazarus.groupLazarusCandidates
import cooking.zap.app.lazarus.isPastEmptyVersion
import cooking.zap.app.lazarus.lazarusUnansweredRelays
import cooking.zap.app.lazarus.mergeLazarusOlderPage
import cooking.zap.app.lazarus.mergeLazarusRetry
import cooking.zap.app.lazarus.parsePrivateTags
import cooking.zap.app.lazarus.scanLazarusKind
import cooking.zap.app.lazarus.sortLazarusCandidates
import cooking.zap.app.lazarus.withLazarusVersion
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerCancelledException
import cooking.zap.app.nostr.SignerDecryptGate
import cooking.zap.app.nostr.SignerRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One kind's scan: chosen but not scanned yet, scanning, the result, or a failed scan. */
sealed class LazarusUiState {
    /** A kind is chosen but not scanned: scans happen only on the user's action. */
    object Idle : LazarusUiState()
    data class Scanning(val kind: Int) : LazarusUiState()
    data class Done(val scan: LazarusScanResult) : LazarusUiState()

    /**
     * The scan failed. [noRelayAnswered] is a scan no relay answered: an
     * error with a retry, never "no versions found". [queriedRelays] and
     * [outcomes] carry what the scan did learn, so the failure can show
     * which relays didn't answer.
     */
    data class Failed(
        val noRelayAnswered: Boolean,
        val queriedRelays: List<String> = emptyList(),
        val outcomes: Map<String, LazarusRelayOutcome>? = null
    ) : LazarusUiState()
}

sealed class LazarusRestoreState {
    object Idle : LazarusRestoreState()
    data class Working(val stage: LazarusRestoreStage) : LazarusRestoreState()

    /** A write relay accepted the recovery; [localUpdated] says the app's own copy was updated too. */
    data class Published(val report: LazarusPublishReport, val localUpdated: Boolean) : LazarusRestoreState()

    /** Signed and sent, but no write relay accepted it. */
    data class NotAccepted(val report: LazarusPublishReport) : LazarusRestoreState()

    /** A newer version appeared before signing: the review now compares against it and asks again. */
    object Changed : LazarusRestoreState()

    /** The app's own copy is newer than anything the relays returned: scan again before restoring. */
    object ChangedLocally : LazarusRestoreState()

    /**
     * No write relay answered the re-read, so nothing was signed. [attempts]
     * counts the failed re-reads in a row: after a failed retry (2+), the
     * explicit override may be offered.
     */
    data class Unconfirmed(val attempts: Int) : LazarusRestoreState()
    object WrongAccount : LazarusRestoreState()
    object SignFailed : LazarusRestoreState()
    object SignerMismatch : LazarusRestoreState()
    object Failed : LazarusRestoreState()
}

/** Why a version can't be restored right now; null when it can. */
enum class LazarusRestoreBlocker {
    /** View-only account: it can scan its history but not restore it. */
    VIEW_ONLY,
    CURRENT,
    /** A past empty version: clobber evidence, never offered for restore (invariant 3). */
    PAST_EMPTY,
    /** The version's (or current's) private items are still being decrypted. */
    DECRYPTING,
    /** The version's private items couldn't be decrypted, so what would be republished is unknown. */
    UNDECRYPTABLE,
    WORKING
}

/**
 * Data recovery (Lazarus): one kind at a time — scan on the user's action,
 * decrypt private items (to self) so ranking and the delta cover them,
 * retry the relays that didn't answer, page back, review a version, and
 * restore it through [LazarusPublisher] on an explicit click.
 */
class LazarusViewModel : ViewModel() {

    val kinds: List<LazarusKindProfile> = getLazarusKindProfiles()

    private var stores: LazarusLocalStores? = null
    private var engine: LazarusScanEngine? = null
    private var publisher: LazarusPublisher? = null
    private var signer: NostrSigner? = null
    private var activePubkey: () -> String? = { null }

    /** The account the scans belong to; switching accounts starts over. */
    private var account: String? = null

    private val _canSign = MutableStateFlow(false)
    /** False for view-only accounts: they can scan, not restore. */
    val canSign: StateFlow<Boolean> = _canSign

    private val _selectedKind = MutableStateFlow<LazarusKindProfile?>(null)
    val selectedKind: StateFlow<LazarusKindProfile?> = _selectedKind

    private val _uiState = MutableStateFlow<LazarusUiState>(LazarusUiState.Idle)
    val uiState: StateFlow<LazarusUiState> = _uiState

    private val _selectedCandidate = MutableStateFlow<LazarusCandidate?>(null)
    val selectedCandidate: StateFlow<LazarusCandidate?> = _selectedCandidate

    private val _delta = MutableStateFlow<LazarusDelta?>(null)
    val delta: StateFlow<LazarusDelta?> = _delta

    private val _profileChanges = MutableStateFlow<List<LazarusProfileChange>?>(null)
    val profileChanges: StateFlow<List<LazarusProfileChange>?> = _profileChanges

    /** True once the re-read before a restore found a newer version than the scan did. */
    private val _changedSinceScan = MutableStateFlow(false)
    val changedSinceScan: StateFlow<Boolean> = _changedSinceScan

    /** Decrypted private items keyed by event id, applied to the ranking and the delta. */
    private val _privateTags = MutableStateFlow<Map<String, List<List<String>>>>(emptyMap())
    val privateTags: StateFlow<Map<String, List<List<String>>>> = _privateTags

    /** Event ids whose encrypted content couldn't be decrypted. Restoring one stays disabled. */
    private val _undecryptable = MutableStateFlow<Set<String>>(emptySet())
    val undecryptable: StateFlow<Set<String>> = _undecryptable

    private val _decrypting = MutableStateFlow<Set<String>>(emptySet())
    val decrypting: StateFlow<Set<String>> = _decrypting

    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups

    private val _showPastEmpty = MutableStateFlow(false)
    val showPastEmpty: StateFlow<Boolean> = _showPastEmpty

    private val _sortOrder = MutableStateFlow(LazarusSortOrder.DATE)
    val sortOrder: StateFlow<LazarusSortOrder> = _sortOrder

    private val _restoreState = MutableStateFlow<LazarusRestoreState>(LazarusRestoreState.Idle)
    val restoreState: StateFlow<LazarusRestoreState> = _restoreState

    /** Bumped on every restore attempt, so the UI re-arms its confirmations each time. */
    private val _restoreAttempt = MutableStateFlow(0)
    val restoreAttempt: StateFlow<Int> = _restoreAttempt

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder

    /** Relays that didn't answer the last "load older" page (0 when it wasn't paged). */
    private val _olderPageFailures = MutableStateFlow(0)
    val olderPageFailures: StateFlow<Int> = _olderPageFailures

    /** The app's default relays: the stand-in write relays for a missing relay list. */
    var standInRelays: List<String> = emptyList()
        private set

    /** Bumped whenever a scan starts or is reset, so late results can't land on a newer one. */
    private var generation = 0
    private var unconfirmedAttempts = 0

    /** Versions an automatic (list) decryption already tried. */
    private val attempted = mutableSetOf<String>()
    private val decryptMutex = Mutex()

    /** Stops automatic decryptions after the user declines signer prompts in a row. */
    private var decryptGate = SignerDecryptGate()

    fun init(
        stores: LazarusLocalStores,
        signer: NostrSigner?,
        canSign: Boolean,
        activePubkey: () -> String?
    ) {
        this.stores = stores
        this.signer = signer
        this.activePubkey = activePubkey
        _canSign.value = canSign && signer != null
        if (engine == null) {
            val e = LazarusScanEngine(
                transport = LazarusSocketTransport(),
                appRelayList = { pk -> this.stores?.relayList(pk) },
                appRelays = { this.stores?.appRelays().orEmpty() }
            )
            engine = e
            publisher = LazarusPublisher(e)
            standInRelays = e.standInDefaults()
        }
    }

    /** The active account. A scan belongs to the account it ran for, so a switch starts over. */
    fun setAccount(pubkey: String?) {
        if (pubkey == account) return
        account = pubkey
        decryptGate = SignerDecryptGate()
        resetScan()
    }

    override fun onCleared() {
        // Leaving the screen releases every connection the scan opened
        engine?.close()
        engine = null
        publisher = null
        super.onCleared()
    }

    private var initialKindOpened = false

    /**
     * Open the kind the screen was entered for (the profile's Restore opens
     * the follow list), once per screen, so a configuration change doesn't
     * undo the user's own choice since.
     */
    fun openInitialKind(kind: Int) {
        if (initialKindOpened) return
        initialKindOpened = true
        openKind(kind)
    }

    /** Choose a kind without scanning it: the user starts the scan. */
    fun openKind(kind: Int) {
        val profile = getLazarusKindProfile(kind) ?: return
        if (_selectedKind.value?.kind == kind) return
        resetScan()
        _selectedKind.value = profile
    }

    /** Choose a kind and scan it. */
    fun selectKind(profile: LazarusKindProfile) {
        if (_uiState.value is LazarusUiState.Scanning || _restoreState.value is LazarusRestoreState.Working) return
        resetScan()
        _selectedKind.value = profile
        scan()
    }

    fun backToKinds() {
        if (_restoreState.value is LazarusRestoreState.Working) return
        resetScan()
        _selectedKind.value = null
    }

    private fun resetScan() {
        generation++
        _uiState.value = LazarusUiState.Idle
        _selectedCandidate.value = null
        _delta.value = null
        _profileChanges.value = null
        _changedSinceScan.value = false
        _privateTags.value = emptyMap()
        _undecryptable.value = emptySet()
        _decrypting.value = emptySet()
        attempted.clear()
        _expandedGroups.value = emptySet()
        _showPastEmpty.value = false
        _sortOrder.value = LazarusSortOrder.DATE
        _restoreState.value = LazarusRestoreState.Idle
        _retrying.value = false
        _loadingOlder.value = false
        _olderPageFailures.value = 0
        unconfirmedAttempts = 0
    }

    /** Scan the chosen kind: the first scan, or a fresh one after a failure or a change. */
    fun scan() {
        val profile = _selectedKind.value ?: return
        val pubkey = account ?: return
        val engine = engine ?: return
        if (_uiState.value is LazarusUiState.Scanning || _restoreState.value is LazarusRestoreState.Working) return
        resetScan()
        val gen = generation
        _uiState.value = LazarusUiState.Scanning(profile.kind)
        viewModelScope.launch {
            val state = try {
                LazarusUiState.Done(scanLazarusKind(profile.kind, pubkey, engine))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LazarusScanFailedException) {
                LazarusUiState.Failed(noRelayAnswered = true, e.queriedRelays, e.outcomes)
            } catch (_: Exception) {
                LazarusUiState.Failed(noRelayAnswered = false)
            }
            if (gen != generation) return@launch
            _uiState.value = state
            decryptShown()
        }
    }

    /** Page further back on the relays that filled a page. */
    fun loadOlder() {
        val profile = _selectedKind.value ?: return
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return
        val pubkey = account ?: return
        val engine = engine ?: return
        if (scan.olderCursors.isEmpty() || _loadingOlder.value || _retrying.value) return
        val gen = generation
        _loadingOlder.value = true
        viewModelScope.launch {
            try {
                val page = engine.fetchVersions(profile.kind, pubkey, scan.olderCursors)
                if (gen != generation) return@launch
                updateScan { mergeLazarusOlderPage(profile, it, page, _privateTags.value) }
                // A page that some relays didn't answer may not be the end of
                // their history; say so, so the user can try again
                _olderPageFailures.value =
                    page.outcomes?.values?.count { it != LazarusRelayOutcome.ANSWERED } ?: 0
                decryptShown()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Paging is best effort; the scan so far stays, and so do its cursors
            } finally {
                if (gen == generation) _loadingOlder.value = false
            }
        }
    }

    /** Ask again just the relays that failed or timed out, without repeating the scan. */
    fun retryUnanswered() {
        val profile = _selectedKind.value ?: return
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return
        val pubkey = account ?: return
        val engine = engine ?: return
        val urls = lazarusUnansweredRelays(scan)
        if (urls.isEmpty() || _retrying.value || _loadingOlder.value) return
        if (_restoreState.value is LazarusRestoreState.Working) return
        val gen = generation
        _retrying.value = true
        viewModelScope.launch {
            try {
                val page = engine.retryRelays(profile.kind, pubkey, urls, scan.writeRelays)
                if (gen != generation) return@launch
                updateScan { mergeLazarusRetry(profile, it, page, _privateTags.value) }
                decryptShown()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed retry leaves the scan as it was
            } finally {
                if (gen == generation) _retrying.value = false
            }
        }
    }

    fun toggleGroup(key: String) {
        _expandedGroups.value = _expandedGroups.value.let { if (key in it) it - key else it + key }
        decryptShown()
    }

    fun setShowPastEmpty(show: Boolean) {
        _showPastEmpty.value = show
        decryptShown()
    }

    fun setSortOrder(order: LazarusSortOrder) {
        _sortOrder.value = order
        decryptShown()
    }

    /** Open (or close, with null) the review of a version, and decrypt what its delta needs. */
    fun selectCandidate(candidate: LazarusCandidate?) {
        if (_restoreState.value is LazarusRestoreState.Working) return
        _selectedCandidate.value = candidate
        _restoreState.value = LazarusRestoreState.Idle
        unconfirmedAttempts = 0
        recomputeReview()
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return
        if (candidate != null) decryptForReview(listOfNotNull(candidate.event, scan.current?.event))
    }

    /** Ask the signer again for a version whose private items couldn't be decrypted. */
    fun retryDecrypt(candidate: LazarusCandidate) {
        _undecryptable.value = _undecryptable.value - candidate.event.id
        decryptForReview(listOf(candidate.event))
    }

    fun dismissRestoreState() {
        if (_restoreState.value !is LazarusRestoreState.Working) {
            _restoreState.value = LazarusRestoreState.Idle
        }
    }

    /** Why [candidate] can't be restored right now, or null when it can. */
    fun restoreBlocker(candidate: LazarusCandidate): LazarusRestoreBlocker? {
        val profile = _selectedKind.value ?: return LazarusRestoreBlocker.WORKING
        if (!_canSign.value) return LazarusRestoreBlocker.VIEW_ONLY
        if (candidate.isCurrent) return LazarusRestoreBlocker.CURRENT
        if (isPastEmptyVersion(candidate, profile)) return LazarusRestoreBlocker.PAST_EMPTY
        if (_restoreState.value is LazarusRestoreState.Working) return LazarusRestoreBlocker.WORKING
        if (profile.privateItemTypes != null) {
            val chosen = candidate.event
            if (getContentEncryption(chosen.content) != null && chosen.id !in _privateTags.value) {
                // What gets republished must be fully known: undecryptable private
                // items keep restore disabled rather than warning and allowing
                return if (chosen.id in _undecryptable.value) LazarusRestoreBlocker.UNDECRYPTABLE
                else LazarusRestoreBlocker.DECRYPTING
            }
            val current = (_uiState.value as? LazarusUiState.Done)?.scan?.current?.event
            if (current != null && current.id in _decrypting.value) return LazarusRestoreBlocker.DECRYPTING
        }
        return null
    }

    /**
     * Restore the version under review. [allowUnconfirmed] is the explicit
     * override the UI offers only after a retry of an unconfirmed re-read
     * failed, behind its own unselected confirmation.
     */
    fun restore(allowUnconfirmed: Boolean = false) {
        val profile = _selectedKind.value ?: return
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return
        val chosen = _selectedCandidate.value ?: return
        val signer = signer ?: return
        val pubkey = account ?: return
        val stores = stores ?: return
        val publisher = publisher ?: return
        if (restoreBlocker(chosen) != null) return
        val gen = generation
        // Same content as the chosen version, so its decrypted private items carry over
        val chosenPrivate = _privateTags.value[chosen.event.id]
        _restoreAttempt.value += 1
        _restoreState.value = LazarusRestoreState.Working(LazarusRestoreStage.CONFIRMING)
        viewModelScope.launch {
            val result = try {
                publisher.restore(
                    chosen = chosen.event,
                    reviewedCurrent = scan.current?.event,
                    pubkey = pubkey,
                    signer = signer,
                    activePubkey = activePubkey,
                    localCopy = stores.localCopy(profile.kind, pubkey),
                    respondingRelays = scan.respondingRelays,
                    allowUnconfirmed = allowUnconfirmed,
                    onStage = { stage -> if (gen == generation) _restoreState.value = LazarusRestoreState.Working(stage) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Update the app's own copy as soon as a write relay accepted the
            // recovery, whatever the screen did meanwhile — unless the active
            // account changed, whose stores now hold another account's lists
            val localUpdated = result is LazarusPublisher.Result.Published && activePubkey() == pubkey
            if (localUpdated) stores.applyRestored((result as LazarusPublisher.Result.Published).report.event, chosenPrivate)
            if (gen != generation) return@launch
            onRestoreResult(profile, chosenPrivate, localUpdated, result)
        }
    }

    private fun onRestoreResult(
        profile: LazarusKindProfile,
        chosenPrivate: List<List<String>>?,
        localUpdated: Boolean,
        result: LazarusPublisher.Result?
    ) {
        _restoreState.value = when (result) {
            is LazarusPublisher.Result.Published -> {
                val restored = result.report.event
                if (chosenPrivate != null) _privateTags.value = _privateTags.value + (restored.id to chosenPrivate)
                // The recovery is now the newest version: show it as current
                updateScan {
                    withLazarusVersion(profile, it, restored, result.report.accepted, _privateTags.value, confirmed = true)
                }
                _selectedCandidate.value = null
                recomputeReview()
                unconfirmedAttempts = 0
                LazarusRestoreState.Published(result.report, localUpdated)
            }
            is LazarusPublisher.Result.NotAccepted -> LazarusRestoreState.NotAccepted(result.report)
            is LazarusPublisher.Result.Changed -> {
                // The newer version becomes current: the list, the recommendation
                // and the delta all measure against it, and the user is asked again
                updateScan {
                    withLazarusVersion(profile, it, result.latest, result.foundOn, _privateTags.value, result.confirmed)
                }
                _changedSinceScan.value = true
                unconfirmedAttempts = 0
                decryptForReview(listOf(result.latest))
                LazarusRestoreState.Changed
            }
            is LazarusPublisher.Result.ChangedLocally -> LazarusRestoreState.ChangedLocally
            LazarusPublisher.Result.Unconfirmed -> {
                unconfirmedAttempts += 1
                LazarusRestoreState.Unconfirmed(unconfirmedAttempts)
            }
            is LazarusPublisher.Result.WrongAccount -> LazarusRestoreState.WrongAccount
            is LazarusPublisher.Result.SignFailed -> LazarusRestoreState.SignFailed
            LazarusPublisher.Result.SignerMismatch -> LazarusRestoreState.SignerMismatch
            null -> LazarusRestoreState.Failed
        }
    }

    private fun updateScan(transform: (LazarusScanResult) -> LazarusScanResult) {
        val done = _uiState.value as? LazarusUiState.Done ?: return
        val updated = transform(done.scan)
        _uiState.value = LazarusUiState.Done(updated)
        // Keep the review pointing at its re-ranked candidate
        _selectedCandidate.value = _selectedCandidate.value?.let { selected ->
            updated.candidates.firstOrNull { it.event.id == selected.event.id } ?: selected
        }
        recomputeReview()
    }

    /** The delta is always measured against the scan's current: the newest version known. */
    private fun recomputeReview() {
        val chosen = _selectedCandidate.value
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan
        if (chosen == null || scan == null) {
            _delta.value = null
            _profileChanges.value = null
            return
        }
        val current = scan.current?.event
        _delta.value = computeLazarusDelta(chosen.event, current, _privateTags.value)
        _profileChanges.value = if (chosen.event.kind == 0) computeLazarusProfileChanges(chosen.event, current) else null
    }

    /**
     * Decrypt what the list shows (spec UI contract): versions on their own
     * rows, expanded groups, and the current and recommended versions. Each
     * decryption can be a signer prompt, so grouped versions wait until their
     * group is expanded or one is reviewed, and a pass is capped.
     */
    private fun decryptShown() {
        val profile = _selectedKind.value ?: return
        val scan = (_uiState.value as? LazarusUiState.Done)?.scan ?: return
        if (profile.privateItemTypes == null || signer == null || !_canSign.value) return
        val shown = if (_sortOrder.value == LazarusSortOrder.SIZE) {
            sortLazarusCandidates(scan.candidates, LazarusSortOrder.SIZE)
                .filter { _showPastEmpty.value || !isPastEmptyVersion(it, profile) }
        } else {
            groupLazarusCandidates(scan, profile, hidePastEmpty = !_showPastEmpty.value).flatMap { item ->
                when (item) {
                    is LazarusListItem.Version -> listOf(item.candidate)
                    is LazarusListItem.Group ->
                        if (lazarusGroupKey(item) in _expandedGroups.value) item.candidates else emptyList()
                }
            }
        }
        val events = (listOfNotNull(scan.current, scan.recommended) + shown)
            .map { it.event }
            .filter { getContentEncryption(it.content) != null && it.id !in attempted && it.id !in _privateTags.value }
            .distinctBy { it.id }
            .take(DECRYPT_CAP)
        if (events.isNotEmpty()) decrypt(events, forReview = false)
    }

    /** Decrypt what a review needs, prompting if it has to: the reviewed version and current. */
    private fun decryptForReview(events: List<NostrEvent>) {
        val profile = _selectedKind.value ?: return
        if (profile.privateItemTypes == null || signer == null || !_canSign.value) return
        val pending = events.filter {
            getContentEncryption(it.content) != null && it.id !in _privateTags.value && it.id !in _undecryptable.value
        }.distinctBy { it.id }
        if (pending.isNotEmpty()) decrypt(pending, forReview = true)
    }

    /**
     * Decrypt private items to self, one version at a time so a prompting
     * signer never gets two requests at once, then re-rank so counts turn
     * exact and the delta covers them. A list pass goes through the session's
     * [SignerDecryptGate] and leaves declined versions estimated (their review
     * asks again); a review marks any failure, so restore stays disabled.
     */
    private fun decrypt(events: List<NostrEvent>, forReview: Boolean) {
        val signer = signer ?: return
        val profile = _selectedKind.value ?: return
        val gen = generation
        _decrypting.value = _decrypting.value + events.map { it.id }
        viewModelScope.launch {
            decryptMutex.withLock {
                for (event in events) {
                    if (gen != generation) break
                    if (event.id in _privateTags.value) continue
                    if (!forReview && (event.id in attempted || !decryptGate.canAttempt(event.content))) continue
                    attempted += event.id
                    val outcome = decryptOne(signer, event)
                    if (gen != generation) break
                    when {
                        outcome.tags != null -> {
                            decryptGate.recordSuccess()
                            _privateTags.value = _privateTags.value + (event.id to outcome.tags)
                        }
                        outcome.declined && !forReview -> decryptGate.recordDenial(event.content)
                        else -> _undecryptable.value = _undecryptable.value + event.id
                    }
                }
            }
            if (gen != generation) return@launch
            _decrypting.value = _decrypting.value - events.map { it.id }.toSet()
            updateScan { applyLazarusPrivateTags(profile, it, _privateTags.value) }
        }
    }

    private class DecryptOutcome(val tags: List<List<String>>?, val declined: Boolean)

    private suspend fun decryptOne(signer: NostrSigner, event: NostrEvent): DecryptOutcome = try {
        val plainText = when (getContentEncryption(event.content)) {
            LazarusEncryption.NIP44 -> signer.nip44Decrypt(event.content, event.pubkey)
            LazarusEncryption.NIP04 -> signer.nip04Decrypt(event.content, event.pubkey)
            null -> null
        }
        DecryptOutcome(plainText?.let(::parsePrivateTags), declined = false)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SignerRejectedException) {
        DecryptOutcome(null, declined = true)
    } catch (_: SignerCancelledException) {
        DecryptOutcome(null, declined = true)
    } catch (_: Exception) {
        DecryptOutcome(null, declined = false)
    }

    companion object {
        /** Upper bound on automatic decryptions per pass; reviews always decrypt what they need. */
        const val DECRYPT_CAP = 20
    }
}

/** A stable key for a group of versions: its newest version's id. */
fun lazarusGroupKey(group: LazarusListItem.Group): String = group.candidates.first().event.id
