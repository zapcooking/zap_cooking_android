package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip gate for [RecipeSerializer] (concern 2.2): take the REAL Tuscan
 * Peposo recipe, serialize it back to a kind-30023 body + tags, re-parse with
 * [RecipeParser], and assert the structured recipe is unchanged. This is what
 * guarantees app-published recipes match web-authored ones.
 */
class RecipeSerializerTest {

    private fun tuscanEvent(): NostrEvent =
        NostrEvent.fromJson(javaClass.getResource("/recipes/tuscan_peposo.json")!!.readText())

    private fun tuscan(): RecipeParser.Recipe = RecipeParser.parse(tuscanEvent())

    /** Build a synthetic 30023 event from serialized content+tags, then parse it. */
    private fun roundTrip(recipe: RecipeParser.Recipe): RecipeParser.Recipe {
        val content = RecipeSerializer.toContent(recipe)
        val tags = RecipeSerializer.toTags(
            title = recipe.title!!,
            summary = recipe.summary,
            imageUrls = listOfNotNull(recipe.image),
            categories = recipe.categories,
        )
        val event = NostrEvent(
            id = "f".repeat(64),
            pubkey = recipe.author,
            created_at = 1_700_000_000L,
            kind = RecipeParser.RECIPE_KIND,
            tags = tags,
            content = content,
            sig = "0".repeat(128),
        )
        return RecipeParser.parse(event)
    }

    @Test
    fun roundTrip_preservesMetadata() {
        val original = tuscan()
        val again = roundTrip(original)
        assertEquals(original.title, again.title)
        assertEquals(original.summary, again.summary)
        assertEquals(original.image, again.image)
        // d-tag = slug(title); for a web-authored recipe that equals the original.
        assertEquals(original.dTag, again.dTag)
        assertEquals(original.categories, again.categories)
    }

    @Test
    fun roundTrip_preservesBody() {
        val original = tuscan()
        val again = roundTrip(original)
        assertEquals(original.content.chefNotes, again.content.chefNotes)
        assertEquals(original.content.details, again.content.details)
        assertEquals(original.content.ingredients, again.content.ingredients)
        assertEquals(original.content.directions, again.content.directions)
        assertEquals(original.content.additionalMarkdown, again.content.additionalMarkdown)
    }

    @Test
    fun roundTrip_preservesHashtagSet() {
        val original = tuscan()
        val again = roundTrip(original)
        // Same set of #t tags (root + per-recipe slug + categories).
        assertEquals(original.hashtags.toSet(), again.hashtags.toSet())
    }

    @Test
    fun serializedContent_isByteIdenticalToWebAuthoredEvent() {
        // The frozen Tuscan event was authored by the web's createMarkdown.
        // Our serializer mirrors it, so re-serializing the parsed recipe must
        // reproduce the original content byte-for-byte (the real "byte-for-byte
        // web parity" gate, beyond the lenient semantic round-trip above).
        val event = tuscanEvent()
        assertEquals(event.content, RecipeSerializer.toContent(RecipeParser.parse(event)))
    }

    @Test
    fun serializedTags_matchWebAuthoredEvent_exceptClientTag() {
        val event = tuscanEvent()
        val recipe = RecipeParser.parse(event)
        val produced = RecipeSerializer.toTags(
            title = recipe.title!!,
            summary = recipe.summary,
            imageUrls = listOfNotNull(recipe.image),
            categories = recipe.categories,
        ).toSet()
        // Web event carries a `client` tag the serializer omits (added at publish).
        val expected = event.tags.filter { it.firstOrNull() != "client" }.toSet()
        assertEquals(expected, produced)
    }

    @Test
    fun slug_isSpacesOnly_keepsParens() {
        assertEquals(
            "tuscan-peposo-(black-pepper-beef-stew)",
            RecipeSerializer.slug("Tuscan Peposo (Black Pepper Beef Stew)"),
        )
    }

    @Test
    fun toContent_usesEmojiDetailLabels_andBulletsAndNumbers() {
        val content = RecipeSerializer.toContent(tuscan())
        assertTrue("⏲️ Prep label", content.contains("- ⏲️ Prep time: 10 min"))
        assertTrue("🍳 Cook label", content.contains("- 🍳 Cook time: 3 hours"))
        assertTrue("ingredient bullet", content.contains("\n- 750 ml red wine\n"))
        assertTrue("numbered direction", content.contains("\n1. Cut beef into large chunks.\n"))
        // Tuscan has no servings → no servings line.
        assertTrue("no servings line", !content.contains("Servings"))
    }

    @Test
    fun toTags_omitsPublishedAt() {
        val tags = RecipeSerializer.toTags("X Y", "s", listOf("u"), listOf("Italian"))
        assertTrue("no published_at", tags.none { it.firstOrNull() == "published_at" })
        assertTrue("d-tag is slug", tags.contains(listOf("d", "x-y")))
        assertTrue("root tag", tags.contains(listOf("t", "zapcooking")))
        assertTrue("per-recipe slug tag", tags.contains(listOf("t", "zapcooking-x-y")))
        assertTrue("category tag slugified", tags.contains(listOf("t", "zapcooking-italian")))
        assertTrue("image tag", tags.contains(listOf("image", "u")))
    }
}
