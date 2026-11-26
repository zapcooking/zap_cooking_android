package cooking.zap.app.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenRecipesTest {

    @Test
    fun exactCoordinate_matchesKnownE2eRecipe() {
        assertTrue(
            HiddenRecipes.isHidden(
                30023,
                "772e4f7ffd63a09748eb231e40e4dbd772fe997b8748c194f6204cfd8e4c933f",
                "e2e-salad",
            ),
        )
    }

    @Test
    fun prefix_hidesAnyPubkeySharingTheLivePublishDTag() {
        val dTag = "ios-2.3-live-publish-1770000000"
        assertTrue(HiddenRecipes.isHidden(30023, "1".repeat(64), dTag))
        assertTrue(HiddenRecipes.isHidden(30023, "2".repeat(64), dTag))
        assertTrue(HiddenRecipes.isHidden("30023:${"1".repeat(64)}:$dTag"))
    }

    @Test
    fun prefix_doesNotHideANeighboringSlug() {
        assertFalse(HiddenRecipes.isHidden(30023, "a".repeat(64), "ios-2.3-live-publish"))
        assertFalse(HiddenRecipes.isHidden(30023, "a".repeat(64), "ios-live-gate-recipe"))
    }
}
