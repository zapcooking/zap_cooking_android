package cooking.zap.app.repo

import cooking.zap.app.R
import cooking.zap.app.repo.InterfacePreferences.StartupTab
import cooking.zap.app.ui.component.BottomTab
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Open-on setting (Interface → Startup): Feed by default, Recipes when
 * the user picks it, restored from storage on the next launch, and a stored
 * value from an older or divergent build never crashes the picker.
 *
 * The load path is [InterfacePreferences.resolveStartupTab] — the same
 * function [InterfacePreferences.getStartupTab] runs the stored value
 * through — so these tests exercise the restore and fallback decisions the
 * settings screen depends on, not just the enum's shape.
 */
class StartupTabTest {

    @Test
    fun `the two choices carry the bar labels, in the bar's order`() {
        assertEquals(listOf(BottomTab.FEED, BottomTab.RECIPES), BottomTab.bottomBarTabs.take(2))
        assertEquals(R.string.nav_feed, BottomTab.FEED.labelResId)
        assertEquals(R.string.nav_recipes, BottomTab.RECIPES.labelResId)
    }

    @Test
    fun `a stored choice is restored as itself`() {
        assertEquals(StartupTab.RECIPES, InterfacePreferences.resolveStartupTab(stored = "recipes"))
        assertEquals(StartupTab.FEED, InterfacePreferences.resolveStartupTab(stored = "feed"))
    }

    @Test
    fun `a missing stored value reads as the Feed default`() {
        assertEquals(StartupTab.FEED, InterfacePreferences.resolveStartupTab(stored = null))
    }

    /**
     * A value an older or divergent build wrote decodes to no choice, which
     * the loader turns into the Feed default — never a crash.
     */
    @Test
    fun `an unknown stored value falls back to Feed`() {
        assertEquals(StartupTab.FEED, InterfacePreferences.resolveStartupTab(stored = "kitchen"))
        assertEquals(StartupTab.FEED, InterfacePreferences.resolveStartupTab(stored = ""))
    }
}
