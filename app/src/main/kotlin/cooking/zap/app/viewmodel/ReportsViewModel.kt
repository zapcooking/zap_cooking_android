package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip56
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.MetadataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Read surface over NIP-56 (kind 1984) reports addressed to me. Subscribes
 * `kinds:[1984] #p:<myPubkey>` on the group/members relay, so an ops account
 * ([Nip56.PANTRY_MOD_ADMINS]) or a room admin — both of whom are p-tagged on reports — sees the
 * reports routed to it. Optionally scoped to a single group (h-tag) for the per-group admin entry.
 *
 * Read-only: it never creates or mutates reports. Cleanup CLOSEs only its own subscription; the
 * shared members-relay connection is left for the group system to manage.
 */
class ReportsViewModel(app: Application) : AndroidViewModel(app) {

    private var relayPool: RelayPool? = null
    private var metadataFetcher: MetadataFetcher? = null
    private var groupFilter: String? = null
    private var subId: String = ""
    private var started = false

    private val seen = HashSet<String>()
    private val collected = mutableListOf<Nip56.ReportInfo>()

    private val _reports = MutableStateFlow<List<Nip56.ReportInfo>>(emptyList())
    val reports: StateFlow<List<Nip56.ReportInfo>> = _reports

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun init(
        pool: RelayPool,
        fetcher: MetadataFetcher,
        myPubkey: String?,
        groupId: String? = null,
    ) {
        if (started) return
        if (myPubkey.isNullOrEmpty()) {
            // No pubkey yet (startup) or READ_ONLY: show the empty state, but DON'T latch — a
            // later init() once the pubkey resolves must still be able to subscribe.
            _loading.value = false
            return
        }
        started = true
        relayPool = pool
        metadataFetcher = fetcher
        groupFilter = groupId
        subId = "reports-${myPubkey.take(12)}${groupId?.let { "-${it.take(8)}" } ?: ""}"

        pool.ensureGroupRelay(RelayConfig.MEMBERS_RELAY)

        viewModelScope.launch(Dispatchers.Default) {
            pool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val report = Nip56.parseReport(relayEvent.event) ?: return@collect
                if (groupFilter != null && report.groupId != groupFilter) return@collect
                if (!seen.add(report.id)) return@collect  // de-dupe across multiple deliveries
                collected.add(report)
                _reports.value = collected.sortedByDescending { it.createdAt }  // newest-first
                _loading.value = false
                metadataFetcher?.queueProfileFetch(report.reporterPubkey)
                metadataFetcher?.queueProfileFetch(report.reportedPubkey)
            }
        }
        viewModelScope.launch {
            pool.eoseSignals.collect { sid -> if (sid == subId) _loading.value = false }
        }
        viewModelScope.launch {
            pool.groupRelayErrors.collect { (url, sid, message) ->
                if (url == RelayConfig.MEMBERS_RELAY && sid == subId) {
                    _error.value = message
                    _loading.value = false
                }
            }
        }
        // Fallback: clear the spinner even if the relay never sends EOSE.
        viewModelScope.launch {
            delay(LOAD_TIMEOUT_MS)
            _loading.value = false
        }

        pool.sendToRelayOrEphemeral(
            RelayConfig.MEMBERS_RELAY,
            ClientMessage.req(
                subId,
                Filter(kinds = listOf(Nip56.KIND_REPORT), pTags = listOf(myPubkey))
            ),
            skipBadCheck = true,
        )
    }

    override fun onCleared() {
        super.onCleared()
        if (subId.isNotEmpty()) {
            relayPool?.sendToRelayOrEphemeral(
                RelayConfig.MEMBERS_RELAY, ClientMessage.close(subId), skipBadCheck = true,
            )
        }
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 8_000L
    }
}
