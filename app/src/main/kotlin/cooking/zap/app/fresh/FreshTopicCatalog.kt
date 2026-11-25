package cooking.zap.app.fresh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FreshTopic(
    val slug: String,
    val name: String,
    /** Posts in the last 14 days (0 when the relay doesn't say). */
    val count14d: Int,
)

data class FreshTopicGroup(
    val slug: String,
    val name: String,
    val count14d: Int,
    val topics: List<FreshTopic>,
)

data class FreshFeaturedTopic(val slug: String, val label: String)

data class FreshSuggestedTopic(
    val slug: String,
    val label: String,
    /** The admins' one-liner, when the list has one. */
    val blurb: String? = null,
    /** A picture for the topic (http/https), when the list has one. */
    val image: String? = null,
    /** The event id the picture came from, when the list names one. */
    val imagePost: String? = null,
    val count14d: Int,
)

/**
 * The feed relay's topic catalog, from its NIP-11 document (`topics`), ported
 * from iOS `FreshTopicCatalog.swift` (web `src/lib/freshFeed/topicList.ts`).
 * Parsing only: the GET and its cache come with the spotlight cards.
 *
 * Shape as measured (iOS build spec §2 "Fresh feed relay", NIP-11):
 * `parents[]` groups `{slug, name, count_14d, topics[]}`, `featured_topics[]`
 * `{slug, label}`, `suggested[]` `{slug, label, blurb, count_14d, image,
 * image_post}`.
 */
data class FreshTopicCatalog(
    val groups: List<FreshTopicGroup>,
    /** null when the relay doesn't publish it. */
    val featured: List<FreshFeaturedTopic>?,
    /** null when the relay doesn't publish it. */
    val suggested: List<FreshSuggestedTopic>?,
) {
    companion object {
        val EMPTY = FreshTopicCatalog(groups = emptyList(), featured = null, suggested = null)

        private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val WEB_IMAGE = Regex("^https?://\\S+$")
        private val EVENT_ID = Regex("^[0-9a-f]{64}$")

        /** The whole catalog from a NIP-11 JSON body; [EMPTY] when it isn't JSON. */
        fun parseNip11(body: String): FreshTopicCatalog {
            val doc = try {
                Json.parseToJsonElement(body)
            } catch (_: Exception) {
                return EMPTY
            }
            return parse(doc)
        }

        fun parse(doc: JsonElement?): FreshTopicCatalog =
            FreshTopicCatalog(parseTopics(doc), parseFeatured(doc), parseSuggested(doc))

        /** The relay's HTTP URL for NIP-11 (wss:// → https://, ws:// → http://). */
        fun nip11Url(relayUrl: String = FeedRelay.URL): String = when {
            relayUrl.startsWith("wss://") -> "https://" + relayUrl.removePrefix("wss://")
            relayUrl.startsWith("ws://") -> "http://" + relayUrl.removePrefix("ws://")
            else -> relayUrl
        }

        /**
         * `topics.parents`, validated; empty when absent or malformed. A group
         * with no valid topics is dropped.
         */
        fun parseTopics(doc: JsonElement?): List<FreshTopicGroup> {
            val parents = topics(doc)?.get("parents") as? JsonArray ?: return emptyList()
            val out = ArrayList<FreshTopicGroup>()
            for (raw in parents) {
                val p = raw as? JsonObject ?: continue
                val (slug, name) = okTopic(p) ?: continue
                val topics = (p["topics"] as? JsonArray).orEmpty().mapNotNull { rawTopic ->
                    val t = rawTopic as? JsonObject ?: return@mapNotNull null
                    val (s, n) = okTopic(t) ?: return@mapNotNull null
                    FreshTopic(s, n, count(t["count_14d"]))
                }
                if (topics.isEmpty()) continue
                out.add(FreshTopicGroup(slug, name, count(p["count_14d"]), topics))
            }
            return out
        }

        /** `topics.featured_topics`, validated; null when the relay doesn't publish it. */
        fun parseFeatured(doc: JsonElement?): List<FreshFeaturedTopic>? {
            val list = topics(doc)?.get("featured_topics") as? JsonArray ?: return null
            return list.mapNotNull { raw ->
                val x = raw as? JsonObject ?: return@mapNotNull null
                val slug = string(x["slug"])?.takeIf(::isSlug) ?: return@mapNotNull null
                val label = string(x["label"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                FreshFeaturedTopic(slug, label)
            }
        }

        /**
         * `topics.suggested`, validated, in the relay's order; null when the
         * relay doesn't publish it. A slug listed twice keeps its first place.
         */
        fun parseSuggested(doc: JsonElement?): List<FreshSuggestedTopic>? {
            val list = topics(doc)?.get("suggested") as? JsonArray ?: return null
            val out = ArrayList<FreshSuggestedTopic>()
            for (raw in list) {
                val t = raw as? JsonObject ?: continue
                val slug = string(t["slug"])?.takeIf(::isSlug) ?: continue
                val label = string(t["label"])?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (out.any { it.slug == slug }) continue
                val blurb = string(t["blurb"])?.trim().orEmpty()
                val image = string(t["image"])?.trim().orEmpty()
                val imagePost = string(t["image_post"])?.lowercase().orEmpty()
                out.add(
                    FreshSuggestedTopic(
                        slug = slug,
                        label = label,
                        blurb = blurb.ifEmpty { null },
                        image = image.takeIf { WEB_IMAGE.matches(it) },
                        imagePost = imagePost.takeIf { EVENT_ID.matches(it) },
                        count14d = count(t["count_14d"]),
                    )
                )
            }
            return out
        }

        /** `^[a-z0-9]+(-[a-z0-9]+)*$` */
        fun isSlug(s: String): Boolean = SLUG.matches(s)

        /**
         * Web `count()`: `Number(x)` finite and > 0, floored; anything else 0.
         * A value too large for `Int` (`1e100`) is 0.
         */
        fun count(raw: JsonElement?): Int {
            val p = raw as? JsonPrimitive ?: return 0
            if (p is JsonNull) return 0
            val n = when {
                p.isString -> p.content.trim().toDoubleOrNull()
                // Web's Number(true) is 1.
                p.content == "true" -> 1.0
                else -> p.content.toDoubleOrNull()
            } ?: return 0
            if (!n.isFinite() || n <= 0.0) return 0
            val floored = kotlin.math.floor(n)
            if (floored > Int.MAX_VALUE.toDouble()) return 0
            return floored.toInt()
        }

        private fun topics(doc: JsonElement?): JsonObject? =
            (doc as? JsonObject)?.get("topics") as? JsonObject

        private fun okTopic(t: JsonObject): Pair<String, String>? {
            val slug = string(t["slug"])?.takeIf(::isSlug) ?: return null
            val name = string(t["name"])?.takeIf { it.isNotEmpty() } ?: return null
            return slug to name
        }

        /** A JSON string's value; null for anything that isn't a string. */
        private fun string(e: JsonElement?): String? =
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
