package cooking.zap.app.ui.component

/**
 * The composer's attachment model: an attachment is a slot on the draft, not
 * text in the editor.
 *
 * `text` never contains an attachment URL. The ordered [ComposerMedia] list is
 * the only ordering that exists — nothing else records it. Per-attachment
 * metadata (alt) hangs off the same slot, so it follows its image through a
 * reorder for free: there is no second structure to keep in step.
 *
 * The wire format does not change. The URL in the content is still what every
 * client reads; [composeNoteContent] appends the slots' URLs at publish, so a
 * note of bare URLs publishes byte-identical to the URL-in-text era.
 *
 * Kept Android-free, like [parseImetaTags], so the hermetic JVM suite can lock
 * the publish-time contract down.
 */

/** One attachment slot. List order is the authoritative publish order. */
data class ComposerMedia(
    val url: String,
    val alt: String? = null,
    val isVideo: Boolean = false,
    val mimeType: String? = null,
    /** "WxH" wire form (NIP-92 `dim`), already stringified. */
    val dimensions: String? = null,
    val thumbhash: String? = null
)

/**
 * What the note says on the wire — also what Preview shows, so the review
 * window previews the note that will go out rather than the half the editor
 * was showing.
 */
fun composeNoteContent(text: String, media: List<ComposerMedia>): String {
    val prose = text.trim()
    val urls = media.map { it.url }.filter { it.isNotEmpty() }
    if (urls.isEmpty()) return prose
    return (if (prose.isNotEmpty()) "$prose\n\n" else "") + urls.joinToString("\n")
}

/**
 * Reorder by splice: remove [from], reinsert at [to]. Returns the input
 * unchanged when either index is out of bounds or the move is a no-op. Because
 * alt text and upload metadata are keyed by URL, the moved item's metadata
 * follows it for free.
 */
fun moveItem(items: List<String>, from: Int, to: Int): List<String> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    val moved = items[from]
    return items.toMutableList().apply {
        removeAt(from)
        add(to, moved)
    }
}

/**
 * Draft migration: strip any line that is exactly an attachment's URL, or
 * publishing appends it a second time.
 *
 * Precise about what it strips — only a boundary occurrence, meaning the URL
 * alone on its line. A URL a person deliberately wrote inside a sentence
 * ("mirror at https://x/a.png if the first dies") is authored prose and
 * survives; a URL twice on one line is ambiguous and is left alone.
 */
fun stripAttachmentUrlLines(text: String, urls: Set<String>): String {
    if (urls.isEmpty()) return text
    return text.split('\n').filterNot { it in urls }.joinToString("\n")
}

private val BARE_URL_LINE_REGEX = Regex("^https?://\\S+$")

/**
 * Bare http(s) URLs that are alone on their line (boundary occurrences) —
 * candidates to OFFER as attachment slots. A URL inside a sentence is
 * authored prose and is never offered. Whitespace around the URL still
 * counts as alone; two URLs on one line match neither (ambiguous).
 */
fun bareUrlLines(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { BARE_URL_LINE_REGEX.matches(it) }.distinct()

/**
 * Removes the first line that is exactly [url] (modulo surrounding
 * whitespace) — the text-side half of attaching a pasted link. Returns the
 * input unchanged when no such line exists.
 */
fun removeBareUrlLine(text: String, url: String): String {
    val lines = text.split('\n')
    val idx = lines.indexOfFirst { it.trim() == url }
    if (idx < 0) return text
    return lines.filterIndexed { i, _ -> i != idx }.joinToString("\n")
}

/**
 * Ordered parse of a draft's private imeta records. Unlike [parseImetaTags]
 * (a url→meta map for rendering), tag order is preserved here because it IS
 * the attachment order — [saveDraft] writes one imeta record per attachment
 * in slot order, undescribed ones included (a draft is private bookkeeping;
 * the published note still emits imeta per its own rules).
 */
fun parseAttachmentTags(tags: List<List<String>>): List<ComposerMedia> {
    val out = mutableListOf<ComposerMedia>()
    for (tag in tags) {
        if (tag.firstOrNull() != "imeta") continue
        var url: String? = null
        var mime: String? = null
        var dim: String? = null
        var thumb: String? = null
        var alt: String? = null
        for (i in 1 until tag.size) {
            val entry = tag[i]
            when {
                entry.startsWith("url ") -> url = entry.removePrefix("url ")
                entry.startsWith("m ") -> mime = entry.removePrefix("m ")
                entry.startsWith("dim ") -> dim = entry.removePrefix("dim ")
                entry.startsWith("thumbhash ") -> thumb = entry.removePrefix("thumbhash ")
                entry.startsWith("alt ") -> alt = entry.removePrefix("alt ")
            }
        }
        url?.let {
            out.add(
                ComposerMedia(
                    url = it,
                    alt = alt?.trim()?.takeIf { s -> s.isNotEmpty() },
                    isVideo = mime?.startsWith("video/") == true,
                    mimeType = mime,
                    dimensions = dim,
                    thumbhash = thumb
                )
            )
        }
    }
    return out
}

/**
 * One-line-per-slot codec for the local last-draft cache: the fast-path
 * restore must rehydrate the attachment slots now that URLs no longer live in
 * the text, or closing and reopening the composer would drop the images.
 * Tab-separated fields with backslash escaping; alt is last so an absent
 * (trailing) field is simply empty.
 */
fun encodeMediaForCache(media: List<ComposerMedia>): String = media.joinToString("\n") { m ->
    listOf(
        m.url,
        m.mimeType.orEmpty(),
        m.dimensions.orEmpty(),
        m.thumbhash.orEmpty(),
        m.alt.orEmpty()
    ).joinToString("\t") { escapeCacheField(it) }
}

/** Inverse of [encodeMediaForCache]; malformed lines are skipped, not fatal. */
fun decodeMediaFromCache(raw: String?): List<ComposerMedia> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split('\n').mapNotNull { line ->
        val parts = line.split('\t').map { unescapeCacheField(it) }
        val url = parts.getOrNull(0).orEmpty()
        if (url.isEmpty() || parts.size != 5) return@mapNotNull null
        val mime = parts[1].ifEmpty { null }
        ComposerMedia(
            url = url,
            mimeType = mime,
            dimensions = parts[2].ifEmpty { null },
            thumbhash = parts[3].ifEmpty { null },
            alt = parts[4].ifEmpty { null },
            isVideo = mime?.startsWith("video/") == true
        )
    }
}

private fun escapeCacheField(s: String): String =
    s.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

private fun unescapeCacheField(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                '\\' -> { sb.append('\\'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                else -> { sb.append(c); i += 1 }
            }
        } else {
            sb.append(c)
            i += 1
        }
    }
    return sb.toString()
}
