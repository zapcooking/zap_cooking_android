package cooking.zap.app.fresh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of iOS `FreshTopicCatalogTests` (web `topicList.test.ts` + `parseSuggested`). */
class FreshTopicCatalogTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private val doc = json(
        """
        {"name":"feed.zap.cooking","topics":{"namespace":"cooking.zap.topic",
          "featured_topics":[{"slug":"meat-seafood","label":"Meat"},{"slug":"cakes","label":" Cakes "}],
          "parents":[
            {"slug":"baking","name":"Baking","count_14d":27,"topics":[
              {"slug":"sourdough","name":"Sourdough","count_14d":6},
              {"slug":"bread","name":"Bread","count_14d":11}]},
            {"slug":"drinks","name":"Drinks","topics":[{"slug":"coffee","name":"Coffee"}]}]}}
        """
    )

    // parseTopics

    @Test
    fun readsGroupsAndTopicsInOrder_withTheir14DayCounts() {
        assertEquals(
            listOf(
                FreshTopicGroup("baking", "Baking", 27, listOf(FreshTopic("sourdough", "Sourdough", 6), FreshTopic("bread", "Bread", 11))),
                FreshTopicGroup("drinks", "Drinks", 0, listOf(FreshTopic("coffee", "Coffee", 0))),
            ),
            FreshTopicCatalog.parseTopics(doc),
        )
    }

    @Test
    fun isEmptyWithoutATopicsField_orWithAMalformedOne() {
        assertTrue(FreshTopicCatalog.parseTopics(json("""{"name":"x"}""")).isEmpty())
        assertTrue(FreshTopicCatalog.parseTopics(null).isEmpty())
        assertTrue(FreshTopicCatalog.parseTopics(json("""{"topics":{"parents":"nope"}}""")).isEmpty())
        assertTrue(FreshTopicCatalog.parseTopics(json("""{"topics":[1,2]}""")).isEmpty())
        assertTrue(FreshTopicCatalog.parseTopics(json("""[]""")).isEmpty())
    }

    @Test
    fun dropsBadSlugsMissingNamesEmptyGroups_badCountsReadAsZero() {
        val d = json(
            """
            {"topics":{"parents":[
              {"slug":"Bad Slug","name":"x","topics":[{"slug":"a","name":"A"}]},
              {"slug":"empty","name":"Empty","topics":[]},
              "junk", null,
              {"slug":"ok","name":"OK","count_14d":-3,"topics":[
                {"slug":"fine","name":"Fine","count_14d":"many"},
                {"slug":"noname"},
                {"slug":"numname","name":7},
                {"slug":"x;drop","name":"X"}]}]}}
            """
        )
        assertEquals(
            listOf(FreshTopicGroup("ok", "OK", 0, listOf(FreshTopic("fine", "Fine", 0)))),
            FreshTopicCatalog.parseTopics(d),
        )
    }

    @Test
    fun slugRule() {
        for (ok in listOf("a", "meat-seafood", "a1-b2-c3", "007")) assertTrue(ok, FreshTopicCatalog.isSlug(ok))
        for (bad in listOf("", "-a", "a-", "a--b", "Bad", "a b", "a_b", "é")) {
            assertEquals(bad, false, FreshTopicCatalog.isSlug(bad))
        }
    }

    /** iOS review fix (#201): a count the integer type cannot hold reads as 0. */
    @Test
    fun counts_outOfRangeReadAsZero_decimalsFloor() {
        assertEquals(0, FreshTopicCatalog.count(JsonPrimitive(1e100)))
        assertEquals(0, FreshTopicCatalog.count(JsonPrimitive("1e100")))
        assertEquals(0, FreshTopicCatalog.count(JsonPrimitive(3_000_000_000L)))
        assertEquals(42, FreshTopicCatalog.count(JsonPrimitive(42.9)))
        assertEquals(40, FreshTopicCatalog.count(JsonPrimitive(" 40 ")))
        assertEquals(1, FreshTopicCatalog.count(JsonPrimitive(true)))
        assertEquals(0, FreshTopicCatalog.count(JsonPrimitive(false)))
        assertEquals(0, FreshTopicCatalog.count(null))
        assertEquals(0, FreshTopicCatalog.count(json("null")))
        assertEquals(0, FreshTopicCatalog.count(json("[5]")))
        val d = json(
            """{"topics":{"parents":[{"slug":"ok","name":"OK","count_14d":1e100,
               "topics":[{"slug":"fine","name":"Fine","count_14d":"1e100"}]}]}}"""
        )
        assertEquals(
            listOf(FreshTopicGroup("ok", "OK", 0, listOf(FreshTopic("fine", "Fine", 0)))),
            FreshTopicCatalog.parseTopics(d),
        )
    }

    // parseFeatured

    @Test
    fun readsSlugsAndTrimmedShortLabelsInOrder() {
        assertEquals(
            listOf(FreshFeaturedTopic("meat-seafood", "Meat"), FreshFeaturedTopic("cakes", "Cakes")),
            FreshTopicCatalog.parseFeatured(doc),
        )
    }

    @Test
    fun featuredNullWhenAbsent_badEntriesDropped() {
        assertNull(FreshTopicCatalog.parseFeatured(json("""{"topics":{"parents":[]}}""")))
        val d = json(
            """{"topics":{"featured_topics":[{"slug":"ok","label":"OK"},{"slug":"Bad","label":"x"},{"slug":"nolabel"}]}}"""
        )
        assertEquals(listOf(FreshFeaturedTopic("ok", "OK")), FreshTopicCatalog.parseFeatured(d))
    }

    // the NIP-11 URL (no network in this PR)

    @Test
    fun nip11IsAskedOfTheFeedRelayOverHttps() {
        assertEquals("https://feed.zap.cooking", FreshTopicCatalog.nip11Url())
        assertEquals("http://localhost:7777", FreshTopicCatalog.nip11Url("ws://localhost:7777"))
    }

    @Test
    fun emptyCatalogFromABodyThatIsNotJson() {
        assertEquals(FreshTopicCatalog.EMPTY, FreshTopicCatalog.parseNip11("offline"))
        assertEquals(FreshTopicCatalog.EMPTY, FreshTopicCatalog.parseNip11(""))
    }

    // parseSuggested

    @Test
    fun suggested_dropsBadEntriesAndRepeats_trimsLabelsAndBlurbs_keepsOnlyWebPictures() {
        val d = json(
            """
            {"topics":{"suggested":[
              {"slug":"Bad Slug","label":"x"},
              {"slug":"ok","label":"  "},
              null,
              {"slug":"beef","label":" Beef ","blurb":"  ","image":"javascript:alert(1)","count_14d":"40"},
              {"slug":"beef","label":"Beef again"},
              {"slug":"pasta","label":"Pasta","image":" https://img.example/p.webp "}]}}
            """
        )
        assertEquals(
            listOf(
                FreshSuggestedTopic(slug = "beef", label = "Beef", count14d = 40),
                FreshSuggestedTopic(slug = "pasta", label = "Pasta", image = "https://img.example/p.webp", count14d = 0),
            ),
            FreshTopicCatalog.parseSuggested(d),
        )
        assertNull(FreshTopicCatalog.parseSuggested(json("""{"topics":{}}""")))
    }

    @Test
    fun suggested_keepsTheRelaysOrder_andIsEmptyWhenMissingOrMalformed() {
        val d = json(
            """
            {"topics":{"suggested":[
              {"slug":"soups-stews","label":"Soups & stews","count_14d":35},
              {"slug":"beer","label":"Beer & brewing","blurb":"Brewed, poured, shared.",
               "image":"https://img.example/beer.jpg","count_14d":11},
              {"slug":"tea","label":"Tea"}]}}
            """
        )
        assertEquals(
            listOf(
                FreshSuggestedTopic(slug = "soups-stews", label = "Soups & stews", count14d = 35),
                FreshSuggestedTopic(
                    slug = "beer", label = "Beer & brewing", blurb = "Brewed, poured, shared.",
                    image = "https://img.example/beer.jpg", count14d = 11,
                ),
                FreshSuggestedTopic(slug = "tea", label = "Tea", count14d = 0),
            ),
            FreshTopicCatalog.parseSuggested(d),
        )
        assertNull(FreshTopicCatalog.parseSuggested(null))
        assertEquals(emptyList<FreshSuggestedTopic>(), FreshTopicCatalog.parseSuggested(json("""{"topics":{"suggested":[]}}""")))
        assertNull(FreshTopicCatalog.parseSuggested(json("""{"topics":{"suggested":"x"}}""")))
    }

    // the measured NIP-11 shape (iOS build spec §2)

    @Test
    fun parsesTheMeasuredDocument_includingImagePost() {
        val post = "ab".repeat(32)
        val catalog = FreshTopicCatalog.parseNip11(
            """
            {"name":"feed.zap.cooking","limitation":{"max_limit":500,"auth_required":false},
             "topics":{"namespace":"cooking.zap.topic",
              "parents":[{"slug":"preserving","name":"Preserving","count_14d":0,
                "topics":[{"slug":"sauerkraut","name":"Sauerkraut","count_14d":0},{"slug":"canning","name":"Canning","count_14d":0}]}],
              "featured_topics":[{"slug":"meat-seafood","label":"Meat"},{"slug":"pasta","label":"Pasta"}],
              "suggested":[{"slug":"pasta","label":"Pasta","blurb":"Noodles.","count_14d":12,
                "image":"https://blossom.example/x.jpg","image_post":"${post.uppercase()}"},
                {"slug":"tea","label":"Tea","image_post":"not-an-id"}]}}
            """
        )
        assertEquals(listOf("preserving"), catalog.groups.map { it.slug })
        assertEquals(listOf(0, 0), catalog.groups.first().topics.map { it.count14d })
        assertEquals(listOf("meat-seafood", "pasta"), catalog.featured?.map { it.slug })
        val suggested = catalog.suggested!!
        assertEquals(post, suggested[0].imagePost)
        assertEquals(12, suggested[0].count14d)
        assertNull(suggested[1].imagePost)
    }
}
