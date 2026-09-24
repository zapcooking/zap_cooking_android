package cooking.zap.app.repo

import android.content.Context

class InterfacePreferences(context: Context) {
    enum class MediaLayoutStyle(val key: String) {
        GALLERY("gallery"),
        STACK("stack");

        companion object {
            fun fromKey(key: String?): MediaLayoutStyle =
                values().firstOrNull { it.key == key } ?: GALLERY
        }
    }

    /** How the notifications list renders each row. */
    enum class NotificationFeedStyle(val key: String) {
        /**
         * Every row renders its detail (referenced note, zap message, poll,
         * reply composer) inline without a tap — the default.
         */
        EXPANDED("expanded"),

        /** One-line rows; tapping opens a single row at a time (accordion). */
        COMPACT("compact");

        companion object {
            fun fromKey(key: String?): NotificationFeedStyle =
                values().firstOrNull { it.key == key } ?: EXPANDED
        }
    }

    /**
     * Light/dark selection. Replaces the old palette picker: Zap Cooking ships
     * one brand palette in a light and a dark cut, and this chooses between
     * them.
     */
    enum class AppearanceMode(val key: String) {
        /** Follow the OS light/dark setting. */
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark");

        companion object {
            fun fromKey(key: String?): AppearanceMode =
                values().firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    fun getAccentColor(): Int = prefs.getInt("accent_color", 0xFFFF5722.toInt())
    fun setAccentColor(colorInt: Int) = prefs.edit().putInt("accent_color", colorInt).apply()

    fun isLargeText(): Boolean = prefs.getBoolean("large_text", false)
    fun setLargeText(enabled: Boolean) = prefs.edit().putBoolean("large_text", enabled).apply()

    fun isNewNotesButtonHidden(): Boolean = prefs.getBoolean("new_notes_button_hidden", false)
    fun setNewNotesButtonHidden(hidden: Boolean) = prefs.edit().putBoolean("new_notes_button_hidden", hidden).apply()

    /**
     * Light/dark preference, migrating anyone who set the old boolean.
     *
     * `dark_theme` was a two-state toggle that defaulted to dark, so it has no
     * way to express "follow the system" — an install that already carries one
     * keeps the mode it was actually looking at rather than being silently
     * flipped to whatever the OS happens to be set to.
     *
     * The absence of that key is ambiguous: either a genuinely fresh install
     * (starts on [AppearanceMode.SYSTEM]) or an upgraded install whose user
     * never toggled — which was RENDERING the old dark default. The two are
     * told apart by whether this app has ever been updated
     * ([isUpdatedSinceInstall]), and the decision is PERSISTED immediately so
     * a later app update can't re-classify a fresh install as upgraded.
     */
    fun getAppearanceMode(): AppearanceMode {
        val stored = prefs.getString(KEY_APPEARANCE, null)
        val hasLegacy = prefs.contains(KEY_LEGACY_DARK_THEME)
        if (stored != null || hasLegacy) {
            return resolveAppearanceMode(
                stored = stored,
                hasLegacyDarkTheme = hasLegacy,
                legacyDarkTheme = prefs.getBoolean(KEY_LEGACY_DARK_THEME, true)
            )
        }
        // Neither key — decide once, then write it down.
        val mode = resolveAppearanceMode(
            stored = null,
            hasLegacyDarkTheme = false,
            legacyDarkTheme = true,
            upgradedInstall = isUpdatedSinceInstall()
        )
        setAppearanceMode(mode)
        return mode
    }

    /** True when this package has received at least one update over its
     *  original install — i.e. it predates the current build. */
    private fun isUpdatedSinceInstall(): Boolean = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    } catch (_: Exception) {
        false
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        prefs.edit()
            .putString(KEY_APPEARANCE, mode.key)
            // Drop the legacy boolean so it can never win a later read.
            .remove(KEY_LEGACY_DARK_THEME)
            .apply()
    }

    fun isClientTagEnabled(): Boolean = prefs.getBoolean("client_tag_enabled", true)
    fun setClientTagEnabled(enabled: Boolean) = prefs.edit().putBoolean("client_tag_enabled", enabled).apply()

    fun isAutoLoadMedia(): Boolean = prefs.getBoolean("auto_load_media", true)
    fun setAutoLoadMedia(enabled: Boolean) = prefs.edit().putBoolean("auto_load_media", enabled).apply()

    fun isVideoAutoPlay(): Boolean = prefs.getBoolean("video_auto_play", true)
    fun setVideoAutoPlay(enabled: Boolean) = prefs.edit().putBoolean("video_auto_play", enabled).apply()

    fun getMediaLayoutStyle(): MediaLayoutStyle =
        MediaLayoutStyle.fromKey(prefs.getString("media_layout_style", null))
    fun setMediaLayoutStyle(style: MediaLayoutStyle) =
        prefs.edit().putString("media_layout_style", style.key).apply()

    /**
     * Display density of the notifications list. Defaults to
     * [NotificationFeedStyle.EXPANDED] so the feed reads end-to-end without
     * tapping every row; the user can flip back to the accordion from the
     * notifications top bar or from interface settings.
     */
    fun getNotificationFeedStyle(): NotificationFeedStyle =
        NotificationFeedStyle.fromKey(prefs.getString(KEY_NOTIFICATION_FEED_STYLE, null))
    fun setNotificationFeedStyle(style: NotificationFeedStyle) =
        prefs.edit().putString(KEY_NOTIFICATION_FEED_STYLE, style.key).apply()

    fun getLanguage(): String = prefs.getString("language", "system") ?: "system"
    fun setLanguage(language: String) = prefs.edit().putString("language", language).apply()

    fun isLiveStreamsHidden(): Boolean = prefs.getBoolean("live_streams_hidden", false)
    fun setLiveStreamsHidden(hidden: Boolean) = prefs.edit().putBoolean("live_streams_hidden", hidden).apply()

    fun isAutoTranslate(): Boolean = prefs.getBoolean("auto_translate", false)
    fun setAutoTranslate(enabled: Boolean) = prefs.edit().putBoolean("auto_translate", enabled).apply()

    fun isPostUndoTimerEnabled(): Boolean = prefs.getBoolean("post_undo_timer_enabled", true)
    fun setPostUndoTimerEnabled(enabled: Boolean) = prefs.edit().putBoolean("post_undo_timer_enabled", enabled).apply()

    fun getPostUndoTimerSeconds(): Int {
        val stored = prefs.getInt("post_undo_timer_seconds", 10)
        return if (stored in postUndoTimerOptions) stored else 10
    }
    fun setPostUndoTimerSeconds(seconds: Int) = prefs.edit().putInt("post_undo_timer_seconds", seconds).apply()

    fun isPostUndoTimerForReplies(): Boolean = prefs.getBoolean("post_undo_timer_for_replies", false)
    fun setPostUndoTimerForReplies(enabled: Boolean) = prefs.edit().putBoolean("post_undo_timer_for_replies", enabled).apply()

    // ── Instant (quick) zaps ────────────────────────────────────────────────
    // Long-press on the zap icon fires immediately at the configured amount
    // when enabled; tap still opens the composer. Keys are per-account via
    // activePubkey so switching accounts never inherits another account's values.

    private fun quickZapKey(base: String): String =
        activePubkey?.let { "${base}_$it" } ?: base

    fun isQuickZapEnabled(): Boolean = prefs.getBoolean(quickZapKey("quick_zap_enabled"), false)
    fun setQuickZapEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(quickZapKey("quick_zap_enabled"), enabled).apply()

    fun getQuickZapAmountSats(): Long =
        prefs.getLong(quickZapKey("quick_zap_amount_sats"), 21L).coerceIn(1L, QUICK_ZAP_MAX_SATS)
    fun setQuickZapAmountSats(amount: Long) {
        prefs.edit().putLong(quickZapKey("quick_zap_amount_sats"), amount.coerceIn(1L, QUICK_ZAP_MAX_SATS)).apply()
    }

    fun getQuickZapMessage(): String = prefs.getString(quickZapKey("quick_zap_message"), "") ?: ""
    fun setQuickZapMessage(message: String) =
        prefs.edit().putString(quickZapKey("quick_zap_message"), message).apply()

    fun reload(pubkey: String?) {
        val wasNull = activePubkey == null
        activePubkey = pubkey
        if (wasNull && pubkey != null) migrateGlobalIfNeeded(pubkey)
    }

    private fun migrateGlobalIfNeeded(pubkey: String) {
        val migKey = "quick_zap_migrated_v1_$pubkey"
        if (prefs.getBoolean(migKey, false)) return
        val edit = prefs.edit().putBoolean(migKey, true)
        if (!prefs.contains("quick_zap_amount_sats_$pubkey") && prefs.contains("quick_zap_amount_sats"))
            edit.putLong("quick_zap_amount_sats_$pubkey", prefs.getLong("quick_zap_amount_sats", 21L))
        if (!prefs.contains("quick_zap_enabled_$pubkey") && prefs.contains("quick_zap_enabled"))
            edit.putBoolean("quick_zap_enabled_$pubkey", prefs.getBoolean("quick_zap_enabled", false))
        if (!prefs.contains("quick_zap_message_$pubkey") && prefs.contains("quick_zap_message"))
            prefs.getString("quick_zap_message", null)?.let { edit.putString("quick_zap_message_$pubkey", it) }
        edit.apply()
    }

    companion object {
        @Volatile var activePubkey: String? = null
        val postUndoTimerOptions = listOf(5, 10, 15, 20, 30)
        const val QUICK_ZAP_MAX_SATS = 10_000L

        /**
         * Pref key backing [NotificationFeedStyle]. Public so observers can
         * filter their `OnSharedPreferenceChangeListener` callbacks by it.
         */
        const val KEY_NOTIFICATION_FEED_STYLE = "notification_feed_style"

        /** Pref key backing [AppearanceMode]. */
        const val KEY_APPEARANCE = "appearance_mode"

        /**
         * The pre-appearance-selector boolean, read once for migration in
         * [getAppearanceMode] and cleared on the first explicit choice.
         */
        const val KEY_LEGACY_DARK_THEME = "dark_theme"

        /**
         * Decide the appearance from what is on disk. Split out from
         * [getAppearanceMode] so the migration rule is testable without a
         * `SharedPreferences` (there is no Robolectric in the JVM suite).
         *
         * An explicit choice always wins. Failing that, an install carrying
         * the old boolean keeps the mode it was actually rendering — that
         * toggle had no "follow the system" state, so inferring one would
         * change the look of an app the user had already set. Only a genuinely
         * fresh install, with neither key, starts on [AppearanceMode.SYSTEM].
         */
        fun resolveAppearanceMode(
            stored: String?,
            hasLegacyDarkTheme: Boolean,
            legacyDarkTheme: Boolean,
            upgradedInstall: Boolean = false
        ): AppearanceMode = when {
            stored != null -> AppearanceMode.fromKey(stored)
            hasLegacyDarkTheme -> if (legacyDarkTheme) AppearanceMode.DARK else AppearanceMode.LIGHT
            // No legacy key at all: an install that has seen an update
            // rendered the old dark-by-default toggle; only a first install
            // follows the system.
            upgradedInstall -> AppearanceMode.DARK
            else -> AppearanceMode.SYSTEM
        }
    }

    /** Reset all interface preferences to defaults (called on full logout). */
    fun reset() {
        prefs.edit()
            .remove("accent_color")
            .remove("theme")
            .remove("large_text")
            .remove("new_notes_button_hidden")
            .remove(KEY_APPEARANCE)
            .remove(KEY_LEGACY_DARK_THEME)
            .remove("balance_hidden")
            .remove("live_streams_hidden")
            .remove("post_undo_timer_enabled")
            .remove("post_undo_timer_seconds")
            .remove("post_undo_timer_for_replies")
            .remove("auto_translate")
            .remove("media_layout_style")
            .remove(KEY_NOTIFICATION_FEED_STYLE)
            .remove("sound_reply")
            .remove("sound_activity")
            .apply()
    }
}
