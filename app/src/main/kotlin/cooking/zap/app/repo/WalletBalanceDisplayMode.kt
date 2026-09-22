package cooking.zap.app.repo

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tri-state balance display for the wallet dashboard. Tapping the
 * balance cycles `SATS → FIAT → HIDDEN → SATS`. Persisted per wallet
 * pubkey under the `walletBalanceDisplay_<pubkey>` key in the
 * `wisp_settings` SharedPreferences file.
 *
 * `FIAT` renders the balance in the user's selected display currency
 * ([CurrencyPreferences]). This is a wallet-only convenience — there is
 * no app-wide "fiat mode"; sats remain the standard everywhere else.
 *
 * `HIDDEN` masks the dashboard balance AND every per-row amount + fee
 * in the transaction history view — useful for screenshots / shoulder-
 * surfing scenarios.
 *
 * Legacy Android global `balance_hidden` Bool is read once per pubkey
 * when no per-pubkey entry exists, and the per-pubkey key is written
 * from it (true → HIDDEN, false → SATS).
 */
enum class WalletBalanceDisplayMode {
    SATS, FIAT, HIDDEN;

    /** Next state in the tap cycle. */
    fun next(): WalletBalanceDisplayMode = when (this) {
        SATS -> FIAT
        FIAT -> HIDDEN
        HIDDEN -> SATS
    }

    companion object {
        private const val KEY_PREFIX = "walletBalanceDisplay_"
        private const val RESTORE_KEY_PREFIX = "walletBalanceDisplayRestore_"
        private const val LEGACY_HIDDEN_KEY = "balance_hidden"

        fun storageKey(pubkey: String): String = "$KEY_PREFIX$pubkey"

        /**
         * Key holding the mode to restore when the drawer mini-wallet's
         * hide toggle un-hides the balance. Same prefix as wisp-ios #474
         * so cross-platform agents stay in lockstep: without it, unhiding
         * from the drawer would reset a FIAT dashboard back to SATS.
         */
        fun restoreStorageKey(pubkey: String): String = "$RESTORE_KEY_PREFIX$pubkey"

        /**
         * Bumped on every persisted change. SharedPreferences isn't
         * observable, and this fork's drawer outlives navigation (it's
         * hoisted above the NavHost, not inside a screen), so the wallet
         * dashboard and the drawer's mini-wallet collect this to know when
         * to re-read — hiding in one place hides in the other.
         */
        private val _changes = MutableStateFlow(0)
        val changes: StateFlow<Int> = _changes

        /**
         * Read the persisted mode for [pubkey]. Falls back to legacy
         * global `balance_hidden` Bool for the first read of a given
         * pubkey, then writes the migrated value so subsequent reads
         * don't depend on the legacy key staying in place. The legacy
         * key itself is left untouched — older builds rolled back keep
         * the prior preference intact.
         *
         * When [pubkey] is null (no signed-in account yet), returns
         * the legacy global state (SATS / HIDDEN only) without
         * touching storage.
         */
        fun read(prefs: SharedPreferences, pubkey: String?): WalletBalanceDisplayMode {
            if (pubkey.isNullOrBlank()) {
                return if (prefs.getBoolean(LEGACY_HIDDEN_KEY, false)) HIDDEN else SATS
            }
            val key = storageKey(pubkey)
            val raw = prefs.getString(key, null)
            if (raw != null) {
                return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SATS
            }
            val initial = if (prefs.getBoolean(LEGACY_HIDDEN_KEY, false)) HIDDEN else SATS
            prefs.edit().putString(key, initial.name.lowercase()).apply()
            return initial
        }

        /** Persist [mode] for [pubkey]. No-op when [pubkey] is null. */
        fun write(prefs: SharedPreferences, pubkey: String?, mode: WalletBalanceDisplayMode) {
            if (pubkey.isNullOrBlank()) return
            prefs.edit().putString(storageKey(pubkey), mode.name.lowercase()).apply()
            _changes.value += 1
        }
    }
}
