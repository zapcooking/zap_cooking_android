package cooking.zap.app.nostr

/**
 * The OnlyFood composer's tag logic, ported from iOS `wisp/OnlyFoodCompose.swift`
 * (which mirrors web `hashtagPills.ts`): the suggested pill set, the body-edit
 * pair that keeps the note text the single source of truth for selected tags,
 * and the reach/cap predicates the suggestion row and the publish confirm gate
 * on. Pure functions — the composer state lives in `ComposeViewModel`.
 */
object OnlyFoodCompose {

    /** First entry of [FoodHashtags.ALL] and the community's canonical food
     *  tag. The publish confirm's one-tap fix. */
    const val DEFAULT_TAG = "foodstr"

    /** The pills, in order — the same set in the same order on web, iOS and
     *  Android: with a scrolling row the order is the design, and the same
     *  person should not see a different menu per device. Every entry is in
     *  [FoodHashtags.ALL_SET] (tested), so a single tapped pill is enough to
     *  reach OnlyFood. Usage-ranked per the iOS file's 60-day kind-1 relay
     *  count; `food` goes last because next to `foodstr` it reads as the
     *  same choice twice. */
    val SUGGESTED_TAGS = listOf(
        "foodstr", "coffee", "cooking", "breakfast", "dinner", "lunch", "cookstr", "food"
    )

    /** The structural cap, from the feed filter that applies it (iOS/web
     *  `OnlyFoodFilter.maxHashtags`): over this many hashtags the filter
     *  drops the note as tag-spam, so the pills stop offering more. */
    const val MAX_TAGS = 20

    /** True when at least one derived hashtag is in the food set — the same
     *  test the feed's filter applies. */
    fun reachesOnlyFood(hashtags: List<String>): Boolean =
        hashtags.any { FoodHashtags.ALL_SET.contains(it.lowercase()) }

    /** The row's candidate order: every food tag already in the body comes
     *  first, so a tag picked from the full set or typed by hand is never
     *  hidden behind the "+"; then the suggested tags that are not yet
     *  selected, in their fixed order. */
    fun rowOrder(bodyTags: List<String>, suggested: List<String> = SUGGESTED_TAGS): List<String> {
        val selected = bodyTags.filter { FoodHashtags.ALL_SET.contains(it.lowercase()) }
        val chosen = selected.map { it.lowercase() }.toSet()
        return selected.map { it.lowercase() } + suggested.filter { it !in chosen }
    }

    /** Structural count the filter sees: distinct `#tag` tokens in the body.
     *  `hashtags` (already deduped by the composer) covers a body the regex
     *  parse might miss. */
    fun tagCount(content: String, hashtags: List<String>): Int = maxOf(
        HASHTAG_TOKEN.findAll(content).map { it.value.drop(1).lowercase() }.distinct().count(),
        hashtags.size
    )

    fun atCap(content: String, hashtags: List<String>): Boolean =
        tagCount(content, hashtags) >= MAX_TAGS

    fun overCap(content: String, hashtags: List<String>): Boolean =
        tagCount(content, hashtags) > MAX_TAGS

    /** The no-food-tag publish-confirm message. At the cap the one-tap fix
     *  can't apply (the toggle is a no-op), so the message says so. */
    fun noFoodTagMessage(content: String, hashtags: List<String>): String {
        val count = tagCount(content, hashtags)
        val base = "This note won't appear in OnlyFood without a food tag"
        if (count < MAX_TAGS) return "$base."
        val toRemove = count - MAX_TAGS + 1
        val remove = if (toRemove == 1) "Remove one" else "Remove $toRemove"
        return "$base, and it already has $count tags. $remove to add #$DEFAULT_TAG."
    }

    /** Append `#tag` to the body. Tags gather on a trailing tag line: if the
     *  last non-blank line is only hashtags, the tag joins it; otherwise it
     *  starts a new paragraph. Trailing whitespace is dropped first so the
     *  tag never lands after stray spaces. */
    fun appending(tag: String, content: String): String {
        val body = content.trimEnd()
        if (body.isEmpty()) return "#$tag"
        val lastLine = body.substringAfterLast('\n')
        return if (isHashtagLine(lastLine)) "$body #$tag" else "$body\n\n#$tag"
    }

    /** Remove every `#tag` token (case-insensitive, whole token) from the
     *  body and tidy the whitespace it leaves behind. */
    fun removing(tag: String, content: String): String {
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}_])#${Regex.escape(tag)}(?![\\p{L}\\p{N}_])",
            RegexOption.IGNORE_CASE
        )
        var out = pattern.replace(content, "")
        // A removed token leaves a run of spaces, a space against a newline,
        // or a space at the very start. Collapse runs until stable: one pass
        // turns three spaces into two.
        while (out.contains("  ")) out = out.replace("  ", " ")
        out = out.replace(" \n", "\n").replace("\n ", "\n")
        while (out.startsWith(" ")) out = out.removePrefix(" ")
        return out.trimEnd()
    }

    /** A line made only of hashtag tokens as the composer derives them
     *  (`#` plus 1–64 letters / digits / underscores). `#foodstr,` is not
     *  one, so a pill after it starts a new paragraph instead of joining
     *  the punctuation. */
    private fun isHashtagLine(line: String): Boolean {
        val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return tokens.all { HASHTAG_TOKEN.matches(it) }
    }

    private val HASHTAG_TOKEN = Regex("#[\\p{L}\\p{N}_]{1,64}")
}
