package cooking.zap.app.nostr

/**
 * NIP-56 reporting (kind 1984).
 *
 * A report carries the reported pubkey as a `p` tag (with the report-type in the 3rd position) and,
 * for a specific message, the reported event as an `e` tag (also typed). The human-facing category
 * (e.g. Child Safety / CSAM) is mapped onto the closest NIP-56 standard type for interoperability,
 * while the precise category label is preserved in the event content so moderators can distinguish,
 * for example, CSAM from generic "illegal".
 */
object Nip56 {
    const val KIND_REPORT = 1984

    /**
     * User-facing report categories. [nip56Type] is the standard NIP-56 report-type emitted on the
     * p/e tags; [label] is the precise category recorded in the report content.
     *
     * NIP-56 standard types are: nudity, malware, profanity, illegal, spam, impersonation, other.
     * "Child Safety / CSAM" and "Harassment" have no dedicated NIP-56 type, so they map to the
     * closest bucket (illegal / other) and stay explicit via the label in content.
     */
    enum class ReportCategory(val nip56Type: String, val label: String) {
        CHILD_SAFETY("illegal", "Child Safety / CSAM"),
        SPAM("spam", "Spam"),
        HARASSMENT("other", "Harassment"),
        ILLEGAL("illegal", "Illegal content"),
        OTHER("other", "Other"),
    }

    /**
     * Known Pantry moderation pubkeys. Reports are addressed to these (extra `p` tags) so ops can
     * find reports routed to them via `kinds:[1984] #p:<adminPubkey>`, in addition to the report
     * being stored on the group relay (`kinds:[1984] #h:<groupId>`).
     */
    val PANTRY_MOD_ADMINS = listOf(
        "a723805cda67251191c8786f4da58f797e6977582301354ba8e91bcb0342dc9c",
        "319ad3e790634dbe86f14db9c2995b26ee3c6228be55f89c4c7fea9acc01d50a",
    )

    /**
     * Build the tags for a kind-1984 report.
     *
     * @param reportedPubkey the offending author (typed `p` tag).
     * @param category the report category (its [ReportCategory.nip56Type] is used on the typed tags).
     * @param eventId the reported message id, if reporting a specific message (typed `e` tag).
     * @param groupId the NIP-29 group id for relay-side association (`h` tag), so admins can query
     *        `kinds:[1984] #h:<groupId>`.
     * @param recipients extra moderator pubkeys to route the report to (untyped `p` tags). The
     *        reported pubkey is never duplicated here.
     */
    fun buildReportTags(
        reportedPubkey: String,
        category: ReportCategory,
        eventId: String? = null,
        groupId: String? = null,
        recipients: List<String> = emptyList(),
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("p", reportedPubkey, category.nip56Type))
        eventId?.takeIf { it.isNotEmpty() }?.let { tags.add(listOf("e", it, category.nip56Type)) }
        groupId?.takeIf { it.isNotEmpty() }?.let { tags.add(listOf("h", it)) }
        recipients.filter { it.isNotEmpty() && it != reportedPubkey }.distinct().forEach {
            tags.add(listOf("p", it))
        }
        return tags
    }

    /** Report content = the precise category label, plus the reporter's optional free-text reason. */
    fun reportContent(category: ReportCategory, reason: String): String = buildString {
        append("[").append(category.label).append("]")
        if (reason.isNotBlank()) append(" ").append(reason.trim())
    }

    /** A parsed kind-1984 report, for the in-app moderation inbox (read side). */
    data class ReportInfo(
        val id: String,
        val reporterPubkey: String,
        val reportedPubkey: String,
        val categoryLabel: String,
        val reason: String,
        val reportedEventId: String?,
        val groupId: String?,
        val createdAt: Long,
    )

    private val CONTENT_REGEX = Regex("^\\[(.+?)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** The NIP-56 standard report types (the 3rd element of a *typed* report `p`/`e` tag). */
    private val REPORT_TYPES = setOf(
        "nudity", "malware", "profanity", "illegal", "spam", "impersonation", "other",
    )

    /**
     * Parse a kind-1984 event into a [ReportInfo], or null if it isn't a usable report.
     *
     * The reported user is the `p` tag whose 3rd element is a known NIP-56 report type (written by
     * [buildReportTags]); a routing/admin `p` tag whose 3rd element is a relay hint won't be
     * mistaken for it. The category label is recovered from the `[Label] reason` content, falling
     * back to the typed tag's NIP-56 type if the content isn't in that shape.
     */
    fun parseReport(event: NostrEvent): ReportInfo? {
        if (event.kind != KIND_REPORT) return null
        val typedP = event.tags.firstOrNull { it.size >= 3 && it[0] == "p" && it[2] in REPORT_TYPES }
            ?: event.tags.firstOrNull { it.size >= 3 && it[0] == "p" }
            ?: return null
        val reportedPubkey = typedP[1]
        val reportedEventId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)

        val match = CONTENT_REGEX.find(event.content.trim())
        val label = match?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { typedP[2] }
        val reason = match?.groupValues?.get(2)?.trim() ?: event.content.trim()

        return ReportInfo(
            id = event.id,
            reporterPubkey = event.pubkey,
            reportedPubkey = reportedPubkey,
            categoryLabel = label,
            reason = reason,
            reportedEventId = reportedEventId,
            groupId = groupId,
            createdAt = event.created_at,
        )
    }
}
