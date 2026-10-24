package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OnlyFood composer's tag logic (ported from iOS `OnlyFoodComposeTests`):
 * the pill row's ordering contract, the body-edit pair that keeps the note
 * text the single source of truth, and the reach/cap predicates the publish
 * confirm gates on.
 */
class OnlyFoodComposeTest {

    @Test
    fun `every suggested pill is a food tag, in the fixed cross-platform order`() {
        assertEquals(
            listOf("foodstr", "coffee", "cooking", "breakfast", "dinner", "lunch", "cookstr", "food"),
            OnlyFoodCompose.SUGGESTED_TAGS
        )
        OnlyFoodCompose.SUGGESTED_TAGS.forEach {
            assertTrue("expected $it in the food set", FoodHashtags.ALL_SET.contains(it))
        }
    }

    @Test
    fun `a single suggested pill is enough to reach OnlyFood`() {
        assertTrue(OnlyFoodCompose.reachesOnlyFood(listOf("coffee")))
        assertFalse(OnlyFoodCompose.reachesOnlyFood(listOf("btc", "lightning")))
        assertTrue(OnlyFoodCompose.reachesOnlyFood(listOf("btc", "steak")))
    }

    @Test
    fun `row order pins body tags first, then unselected suggestions`() {
        val body = listOf("pizza", "coffee", "gm")
        assertEquals(
            listOf("pizza", "coffee", "foodstr", "cooking", "breakfast", "dinner", "lunch", "cookstr", "food"),
            OnlyFoodCompose.rowOrder(body)
        )
    }

    @Test
    fun `appending gathers tags on a trailing hashtag line`() {
        assertEquals("#coffee", OnlyFoodCompose.appending("coffee", ""))
        assertEquals("Dinner!\n\n#coffee", OnlyFoodCompose.appending("coffee", "Dinner!"))
        // Joins an existing tag line…
        assertEquals("Dinner!\n\n#gm #coffee", OnlyFoodCompose.appending("coffee", "Dinner!\n\n#gm"))
        // …but not a line with punctuation, which starts a new paragraph
        assertEquals("Nice! #gm, right\n\n#coffee", OnlyFoodCompose.appending("coffee", "Nice! #gm, right"))
        // Trailing whitespace is dropped first
        assertEquals("Dinner!\n\n#coffee", OnlyFoodCompose.appending("coffee", "Dinner!  \n"))
    }

    @Test
    fun `removing strips every token and tidies whitespace`() {
        assertEquals("gm", OnlyFoodCompose.removing("coffee", content = "#coffee gm"))
        assertEquals("gm", OnlyFoodCompose.removing("coffee", content = "gm #coffee"))
        assertEquals("gm", OnlyFoodCompose.removing("coffee", content = "#COFFEE gm"))
        // Only whole tokens: #coffeetable survives
        assertEquals("#coffeetable gm", OnlyFoodCompose.removing("coffee", content = "#coffeetable gm"))
        // A solo tag line collapses away
        assertEquals("Dinner!", OnlyFoodCompose.removing("coffee", content = "Dinner!\n\n#coffee"))
    }

    @Test
    fun `append then remove round-trips the body`() {
        val original = "Soup day\n\n#gm everyone"
        val appended = OnlyFoodCompose.appending("soup", content = original)
        assertEquals("Soup day\n\n#gm everyone\n\n#soup", appended)
        assertEquals(original, OnlyFoodCompose.removing("soup", content = appended))
    }

    @Test
    fun `the cap counts distinct body tokens and gates the pills`() {
        val many = (1..OnlyFoodCompose.MAX_TAGS).joinToString(" ") { "#t$it" }
        assertTrue(OnlyFoodCompose.atCap(many, emptyList()))
        assertFalse(OnlyFoodCompose.overCap(many, emptyList()))
        val tooMany = many + " #extra"
        assertTrue(OnlyFoodCompose.overCap(tooMany, emptyList()))
        // Case-insensitive dedupe: #Tag and #tag are one structural tag
        val twice = "#tag #TAG"
        assertEquals(1, OnlyFoodCompose.tagCount(twice, emptyList()))
    }

    @Test
    fun `the no-tag message offers the one-tap fix until the cap`() {
        assertEquals(
            "This note won't appear in OnlyFood without a food tag.",
            OnlyFoodCompose.noFoodTagMessage("hello", emptyList())
        )
        val atCapBody = (1..OnlyFoodCompose.MAX_TAGS).joinToString(" ") { "#t$it" }
        assertTrue(
            OnlyFoodCompose.noFoodTagMessage(atCapBody, emptyList())
                .startsWith("This note won't appear in OnlyFood without a food tag, and it already has")
        )
    }
}
