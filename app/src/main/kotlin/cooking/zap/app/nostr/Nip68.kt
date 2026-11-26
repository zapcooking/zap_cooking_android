package cooking.zap.app.nostr

object Nip68 {
    const val KIND_PICTURE = 20

    data class ImetaEntry(
        val url: String,
        val mimeType: String? = null,
        val thumbhash: String? = null,
        val blurhash: String? = null,
        val dim: String? = null,
        val alt: String? = null,
        val hash: String? = null,
        val fallback: List<String> = emptyList()
    )

    fun parseImetaEntries(event: NostrEvent): List<ImetaEntry> {
        return event.tags.filter { it.firstOrNull() == "imeta" && it.size > 1 }
            .mapNotNull { tag ->
                val fields = mutableMapOf<String, String>()
                val fallbacks = mutableListOf<String>()
                for (i in 1 until tag.size) {
                    val part = tag[i]
                    val spaceIdx = part.indexOf(' ')
                    if (spaceIdx > 0) {
                        val key = part.substring(0, spaceIdx)
                        val value = part.substring(spaceIdx + 1)
                        if (key == "fallback") fallbacks.add(value)
                        else fields[key] = value
                    }
                }
                val url = fields["url"] ?: return@mapNotNull null
                ImetaEntry(
                    url = url,
                    mimeType = fields["m"],
                    thumbhash = fields["thumbhash"],
                    blurhash = fields["blurhash"],
                    dim = fields["dim"],
                    alt = fields["alt"]?.let { normalizeAltBreaks(it) }?.takeIf { it.isNotEmpty() },
                    hash = fields["x"],
                    fallback = fallbacks
                )
            }
    }

    fun buildPictureTags(
        title: String?,
        media: List<ImetaEntry>,
        hashtags: List<String> = emptyList(),
        contentWarning: String? = null
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        if (!title.isNullOrBlank()) {
            tags.add(listOf("title", title))
        }
        for (entry in media) {
            val imetaParts = mutableListOf("imeta")
            imetaParts.add("url ${entry.url}")
            entry.mimeType?.let { imetaParts.add("m $it") }
            entry.thumbhash?.let { imetaParts.add("thumbhash $it") }
            entry.blurhash?.let { imetaParts.add("blurhash $it") }
            entry.dim?.let { imetaParts.add("dim $it") }
            entry.alt?.let { normalizeAltBreaks(it) }?.takeIf { it.isNotEmpty() }?.let { imetaParts.add("alt $it") }
            entry.hash?.let { imetaParts.add("x $it") }
            for (fb in entry.fallback) imetaParts.add("fallback $fb")
            tags.add(imetaParts)
        }
        for (hashtag in hashtags) {
            tags.add(listOf("t", hashtag))
        }
        contentWarning?.let { tags.add(listOf("content-warning", it)) }
        return tags
    }

    /**
     * Normalizes an `alt` value's line breaks per the imeta linebreak
     * contract (wisp-ios #472): CRLF/CR → LF, each line's surrounding
     * whitespace trimmed, runs of 3+ newlines capped at one blank line,
     * ends trimmed. Single breaks and a single paragraph gap survive —
     * multi-paragraph descriptions are the point. Applied when publishing
     * an `alt` slot and when parsing one, so third-party alt can't balloon
     * the layout either. Slot parsing still splits on the first space only,
     * so interior breaks belong to the value and reach every render surface.
     */
    fun normalizeAltBreaks(text: String): String {
        val lf = text.replace("\r\n", "\n").replace("\r", "\n")
        val trimmedLines = lf.split('\n').joinToString("\n") { it.trim() }
        val capped = trimmedLines.replace(Regex("\n{3,}"), "\n\n")
        return capped.trim()
    }

    fun getTitle(event: NostrEvent): String? {
        return event.tags.firstOrNull { it.firstOrNull() == "title" && it.size > 1 }?.get(1)
    }

    fun isPicture(event: NostrEvent): Boolean = event.kind == KIND_PICTURE
}
