package cooking.zap.app.repo

import cooking.zap.app.repo.InterfacePreferences.AppearanceMode
import cooking.zap.app.repo.InterfacePreferences.Companion.resolveAppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings screen now offers System/Light/Dark instead of sixteen color
 * schemes, so the old `dark_theme` boolean has to become a three-state mode.
 *
 * The boolean had no "follow the system" state and defaulted to dark, so
 * reading a missing key as "system" would silently re-skin an app the user had
 * already set — that's the case these pin down.
 */
class AppearanceModeTest {

    @Test
    fun `a fresh install follows the system`() {
        assertEquals(
            AppearanceMode.SYSTEM,
            resolveAppearanceMode(stored = null, hasLegacyDarkTheme = false, legacyDarkTheme = true)
        )
    }

    @Test
    fun `an explicit choice wins over the legacy boolean`() {
        assertEquals(
            AppearanceMode.LIGHT,
            resolveAppearanceMode(stored = "light", hasLegacyDarkTheme = true, legacyDarkTheme = true)
        )
        assertEquals(
            AppearanceMode.SYSTEM,
            resolveAppearanceMode(stored = "system", hasLegacyDarkTheme = true, legacyDarkTheme = true)
        )
    }

    /**
     * Someone who was looking at the dark app keeps the dark app, even on a
     * phone whose OS is set to light.
     */
    @Test
    fun `the legacy dark toggle migrates to dark`() {
        assertEquals(
            AppearanceMode.DARK,
            resolveAppearanceMode(stored = null, hasLegacyDarkTheme = true, legacyDarkTheme = true)
        )
    }

    @Test
    fun `the legacy light toggle migrates to light`() {
        assertEquals(
            AppearanceMode.LIGHT,
            resolveAppearanceMode(stored = null, hasLegacyDarkTheme = true, legacyDarkTheme = false)
        )
    }

    /** An unrecognized stored value must not crash or blank the screen. */
    @Test
    fun `an unknown stored value falls back to system`() {
        assertEquals(
            AppearanceMode.SYSTEM,
            resolveAppearanceMode(stored = "nord", hasLegacyDarkTheme = false, legacyDarkTheme = true)
        )
    }

    @Test
    fun `every mode round-trips through its key`() {
        AppearanceMode.values().forEach { mode ->
            assertEquals(mode, AppearanceMode.fromKey(mode.key))
        }
    }
}
