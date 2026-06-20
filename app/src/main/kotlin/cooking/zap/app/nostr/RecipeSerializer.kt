package cooking.zap.app.nostr

/**
 * Serializes a [RecipeParser.Recipe] into a kind-30023 recipe body + tags —
 * the inverse of [RecipeParser], for publishing recipes (concern 2.2). Mirrors
 * the zap.cooking web create flow byte-for-byte (`createMarkdown` +
 * `create/+page.svelte` tag-building) so app-authored recipes round-trip
 * against web-authored ones.
 *
 * Pure (string ops only) — round-trip unit-tested (serialize → [RecipeParser]
 * → equals).
 */
object RecipeSerializer {

    /** New recipes always carry the current root tag (never legacy nostrcooking). */
    private val ROOT = RecipeParser.RECIPE_HASHTAGS.first() // "zapcooking"

    /**
     * The web's exact d-tag/slug: `title.toLowerCase().replaceAll(' ', '-')` —
     * **spaces only**. Parens/slashes are deliberately kept (e.g.
     * `tuscan-peposo-(black-pepper-beef-stew)`); the recipe route already
     * URL-encodes them. Do not "clean up" further or recipes won't round-trip
     * against web-authored ones. Same title → same d-tag (replaces) — web
     * behavior; mirrored, not de-duplicated.
     */
    fun slug(title: String): String =
        // Locale.ROOT, not the device locale: JS `toLowerCase()` is locale-
        // independent, and a Turkish-locale dotless-i would break web parity
        // and the "same title → same d-tag" guarantee.
        title.lowercase(java.util.Locale.ROOT).replace(" ", "-")

    /** Structured body → `##`-section markdown (mirrors web `createMarkdown`). */
    fun toContent(recipe: RecipeParser.Recipe): String {
        val c = recipe.content
        return buildString {
            c.chefNotes?.takeIf { it.isNotBlank() }?.let { append("\n## Chef's notes\n\n$it\n") }

            val d = c.details
            if (d.prepTime != null || d.cookTime != null || d.servings != null) {
                append("\n## Details\n\n")
                d.prepTime?.let { append("- ⏲️ Prep time: $it\n") }
                d.cookTime?.let { append("- 🍳 Cook time: $it\n") }
                d.servings?.let { append("- 🍽️ Servings: $it\n") }
            }

            if (c.ingredients.isNotEmpty()) {
                append("\n## Ingredients\n\n")
                c.ingredients.forEach { append("- $it\n") }
            }

            if (c.directions.isNotEmpty()) {
                append("\n\n## Directions\n\n")
                c.directions.forEachIndexed { i, step -> append("${i + 1}. $step\n") }
            }

            c.additionalMarkdown?.takeIf { it.isNotBlank() }
                ?.let { append("\n\n## Additional Resources\n\n$it\n\n") }
        }
    }

    /**
     * kind-30023 tags for a NEW recipe (mirrors the web create flow):
     * `d`=slug, `title`, `t:zapcooking`, `t:zapcooking-<slug>`, `summary` (if
     * set), one `image` per URL, and `t:zapcooking-<category-slug>` per
     * category. **No `published_at`** — the web omits it (RecipeParser falls
     * back to `created_at`). The `client` tag is added at publish, not here.
     */
    fun toTags(
        title: String,
        summary: String?,
        imageUrls: List<String>,
        categories: List<String>,
    ): List<List<String>> = buildList {
        val slug = slug(title)
        add(listOf("d", slug))
        add(listOf("title", title))
        add(listOf("t", ROOT))
        add(listOf("t", "$ROOT-$slug"))
        if (!summary.isNullOrBlank()) add(listOf("summary", summary))
        imageUrls.forEach { add(listOf("image", it)) }
        // Categories are raw display names → `zapcooking-<slug>`, same slug fn.
        categories.filter { it.isNotBlank() }.forEach { add(listOf("t", "$ROOT-${slug(it)}")) }
    }
}
