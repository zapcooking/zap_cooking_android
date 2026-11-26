package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.ScheduleFailure
import cooking.zap.app.api.ScheduledItem
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip09
import cooking.zap.app.nostr.Nip37
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.DeletedEventsRepository
import cooking.zap.app.repo.LastDraftCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DraftsViewModel(app: Application) : AndroidViewModel(app) {

    // Shared with ComposeViewModel's "continue where you left off" fast path: deleting a draft
    // here must also drop the local cache, or the composer silently restores the deleted text.
    private val lastDraftCache = LastDraftCache(app)

    private val _drafts = MutableStateFlow<List<Nip37.Draft>>(emptyList())
    val drafts: StateFlow<List<Nip37.Draft>> = _drafts

    /** Pending soonest first, then failed; see [orderScheduled]. */
    private val _scheduledPosts = MutableStateFlow<List<ScheduledItem>>(emptyList())
    val scheduledPosts: StateFlow<List<ScheduledItem>> = _scheduledPosts

    /** First load with nothing to show yet (full-screen spinner). */
    private val _scheduledLoading = MutableStateFlow(false)
    val scheduledLoading: StateFlow<Boolean> = _scheduledLoading

    /** A pull-to-refresh is in flight (the pull indicator). */
    private val _scheduledRefreshing = MutableStateFlow(false)
    val scheduledRefreshing: StateFlow<Boolean> = _scheduledRefreshing

    /** Why the last load failed (a string resource), or null. Rows from an earlier load stay visible. */
    private val _scheduledError = MutableStateFlow<Int?>(null)
    val scheduledError: StateFlow<Int?> = _scheduledError

    /** Rows whose cancel request has not answered yet. */
    private val _cancellingIds = MutableStateFlow<Set<String>>(emptySet())
    val cancellingIds: StateFlow<Set<String>> = _cancellingIds

    /** One-shot messages about a cancel (string resources), for a snackbar. */
    private val _scheduledMessages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val scheduledMessages: SharedFlow<Int> = _scheduledMessages

    private var scheduledLists = ScheduledLists()
    private var scheduledLoadGeneration = 0

    private var draftsLoaded = false
    private val draftsSubId = "drafts_${System.currentTimeMillis()}"
    private var scheduledJob: Job? = null

    fun loadDrafts(relayPool: RelayPool, signer: NostrSigner?, deletedEventsRepo: DeletedEventsRepository? = null) {
        if (draftsLoaded || signer == null) return
        draftsLoaded = true

        val filter = Filter(
            kinds = listOf(Nip37.KIND_DRAFT),
            authors = listOf(signer.pubkeyHex),
            limit = 50
        )
        viewModelScope.launch(Dispatchers.Default) {
            // Ensure write relays are connected before querying — they may be idle right after launch,
            // in which case a bare REQ reaches 0 relays and the drafts list comes back empty.
            val req = ClientMessage.req(draftsSubId, filter)
            var reqSent = relayPool.sendToWriteRelays(req)
            if (reqSent == 0 && relayPool.ensureWriteRelaysConnected(2_000) > 0) {
                reqSent = relayPool.sendToWriteRelays(req)
            }
            // Match the save fallback: drafts may live on read relays for accounts without
            // reachable write relays, so query every connected relay when write relays yield none.
            if (reqSent == 0) relayPool.sendToAllRelays(req)

            // Track the newest version seen per draft coordinate so an older copy arriving from
            // a lagging relay can't resurrect a draft that was superseded or emptied (deleted).
            val latestCreatedAt = HashMap<String, Long>()

            relayPool.events.collect { event ->
                if (event.kind != Nip37.KIND_DRAFT) return@collect
                if (event.pubkey != signer.pubkeyHex) return@collect
                val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return@collect

                val prev = latestCreatedAt[dTag]
                if (prev != null && event.created_at < prev) return@collect

                try {
                    // NIP-37 deletes a draft by publishing an empty replacement — the decrypted
                    // content is then blank. Match iOS Wisp: show every non-empty draft and do not
                    // cross-reference the kind-5 deletion registry, which hid drafts iOS still shows.
                    val decrypted = signer.nip44Decrypt(event.content, signer.pubkeyHex)
                    if (decrypted.isBlank()) {
                        // Empty replacement = deletion; a valid outcome, so advance the marker.
                        latestCreatedAt[dTag] = event.created_at
                        _drafts.value = _drafts.value.filter { it.dTag != dTag }
                        return@collect
                    }
                    val draft = Nip37.parseDraft(event, decrypted) ?: return@collect
                    // Only advance the newest-seen marker once the event decrypts and parses
                    // cleanly, so a malformed/undecryptable newest copy can't poison the
                    // coordinate and permanently suppress older valid copies from other relays.
                    latestCreatedAt[dTag] = event.created_at
                    val current = _drafts.value.toMutableList()
                    val idx = current.indexOfFirst { it.dTag == draft.dTag }
                    if (idx >= 0) current[idx] = draft else current.add(draft)
                    _drafts.value = current.sortedByDescending { it.createdAt }
                } catch (_: Exception) {
                    // Decryption/parse failed — skip without advancing latestCreatedAt so a
                    // valid older copy from another relay can still win.
                }
            }
        }
    }

    private fun publishScheduled(lists: ScheduledLists) {
        scheduledLists = lists
        _scheduledPosts.value = lists.rows
        _scheduledError.value = lists.failure?.let(::scheduleListFailureMessageRes)
    }

    /**
     * Load the scheduled list from the Zap Cooking scheduler API: `?status=pending`, then
     * `?status=failed` ([loadScheduledLists]). Called on every screen entry and on pull to
     * refresh ([userInitiated]), so a post scheduled a moment ago in the composer — or on
     * the web — is there the next time the screen opens. Rows already shown stay visible
     * while it runs.
     */
    fun loadScheduledPosts(api: ZapCookingApi, signer: NostrSigner?, userInitiated: Boolean = false) {
        scheduledJob?.cancel()
        val generation = ++scheduledLoadGeneration
        if (signer == null) {
            publishScheduled(ScheduledLists(failure = ScheduleFailure.SignedOut))
            _scheduledLoading.value = false
            _scheduledRefreshing.value = false
            return
        }
        _scheduledLoading.value = _scheduledPosts.value.isEmpty() && !userInitiated
        _scheduledRefreshing.value = userInitiated

        scheduledJob = viewModelScope.launch {
            try {
                val loaded = loadScheduledLists(
                    previous = scheduledLists,
                    list = { status -> api.listScheduled(signer, status) },
                    emit = { lists ->
                        // Pending is on screen as soon as it returns; the spinner does not
                        // wait for the failed call.
                        if (generation == scheduledLoadGeneration) {
                            publishScheduled(lists)
                            _scheduledLoading.value = false
                        }
                    },
                )
                if (generation == scheduledLoadGeneration) publishScheduled(loaded)
            } finally {
                // A newer load owns the flags once it has started.
                if (generation == scheduledLoadGeneration) {
                    _scheduledLoading.value = false
                    _scheduledRefreshing.value = false
                }
            }
        }
    }

    /**
     * Cancel a pending scheduled post. The row stays until the server answers
     * ([cancelOutcome]): it is removed only when the post really is cancelled or no longer
     * pending, and kept — with the reason — when the cancel did not happen. The list is
     * refreshed afterwards either way.
     */
    fun cancelScheduledPost(id: String, api: ZapCookingApi, signer: NostrSigner?) {
        if (id in _cancellingIds.value) return
        _cancellingIds.value = _cancellingIds.value + id
        viewModelScope.launch {
            try {
                val outcome = cancelOutcome(api.cancelScheduled(signer, id))
                if (outcome.removeRow) publishScheduled(scheduledLists.without(id))
                outcome.messageRes?.let { _scheduledMessages.tryEmit(it) }
            } finally {
                _cancellingIds.value = _cancellingIds.value - id
            }
            loadScheduledPosts(api, signer)
        }
    }

    fun deleteDraft(
        dTag: String,
        relayPool: RelayPool,
        signer: NostrSigner?,
        deletedEventsRepo: DeletedEventsRepository? = null
    ) {
        if (signer == null) return
        removeDraftLocally(dTag)
        // Drop the local compose fast-path cache when it points at this draft — otherwise the
        // composer resurrects the deleted text on next open regardless of relay propagation.
        lastDraftCache.clearIfId(signer.pubkeyHex, dTag)
        // Persist locally first so any future relay response for this coord is silently dropped,
        // even before the kind 5 deletion propagates.
        deletedEventsRepo?.markDeletedAddress(Nip37.KIND_DRAFT, signer.pubkeyHex, dTag)

        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            // NIP-09: publish an addressable-delete (kind 5 with "a" tag) so relays drop the draft.
            val deletionTags = Nip09.buildAddressableDeletionTags(Nip37.KIND_DRAFT, signer.pubkeyHex, dTag)
            val deleteEvent = DraftDeleteSigning.signOrNull(
                label = "draft delete (kind 5)",
                normal = { signer.signEvent(kind = 5, content = "", tags = deletionTags) },
                silent = { signer.signEventSilently(kind = 5, content = "", tags = deletionTags) }
            )
            if (deleteEvent != null) sendWithFallback(relayPool, ClientMessage.event(deleteEvent))

            // Also publish an empty-content replacement so clients that don't honor NIP-09 (or that
            // use the replace-by-address semantics of NIP-37) still observe deletion. Skip it when
            // the kind-5 sign already failed terminally, so we don't re-launch the signer UI a
            // second time for the same user action.
            val replacement = if (deleteEvent == null) null else {
                val replacementTags = Nip37.buildDraftTags(dTag, 1)
                DraftDeleteSigning.signOrNull(
                    label = "draft delete (empty replacement)",
                    normal = {
                        val encrypted = signer.nip44Encrypt("", signer.pubkeyHex)
                        signer.signEvent(kind = Nip37.KIND_DRAFT, content = encrypted, tags = replacementTags)
                    },
                    silent = {
                        val encrypted = signer.nip44EncryptSilently("", signer.pubkeyHex)
                        if (encrypted == null) null
                        else signer.signEventSilently(kind = Nip37.KIND_DRAFT, content = encrypted, tags = replacementTags)
                    }
                )
            }
            if (replacement != null) sendWithFallback(relayPool, ClientMessage.event(replacement))

            // One toast per delete action if either deletion event ultimately failed to sign.
            if (deleteEvent == null || replacement == null) DraftDeleteSigning.toastFailed(app)
        }
    }

    /**
     * Mirror ComposeViewModel.saveDraft's relay fallback: a draft may have been persisted via
     * sendToAllRelays (no reachable write relays), so its deletion must be able to reach those
     * same relays or the "deleted" draft reappears. Write relays → reconnect+retry → all relays.
     */
    private suspend fun sendWithFallback(relayPool: RelayPool, message: String) {
        var sent = relayPool.sendToWriteRelays(message)
        if (sent == 0 && relayPool.ensureWriteRelaysConnected(2_000) > 0) {
            sent = relayPool.sendToWriteRelays(message)
        }
        if (sent == 0) relayPool.sendToAllRelays(message)
    }

    fun addDraftLocally(draft: Nip37.Draft) {
        val current = _drafts.value.toMutableList()
        val idx = current.indexOfFirst { it.dTag == draft.dTag }
        if (idx >= 0) {
            current[idx] = draft
        } else {
            current.add(draft)
        }
        _drafts.value = current.sortedByDescending { it.createdAt }
    }

    fun removeDraftLocally(dTag: String) {
        _drafts.value = _drafts.value.filter { it.dTag != dTag }
    }

    fun resetLoadState() {
        draftsLoaded = false
        scheduledJob?.cancel()
        scheduledJob = null
        scheduledLoadGeneration++
        scheduledLists = ScheduledLists()
        _scheduledPosts.value = emptyList()
        _scheduledLoading.value = false
        _scheduledRefreshing.value = false
        _scheduledError.value = null
        _cancellingIds.value = emptySet()
    }
}
