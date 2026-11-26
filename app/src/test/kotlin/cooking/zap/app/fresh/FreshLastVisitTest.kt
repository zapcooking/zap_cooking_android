package cooking.zap.app.fresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Port of iOS `FreshLastVisitTests` (web `lastVisit.test.ts`), one case for one. */
class FreshLastVisitTest {
    private class Store : FreshVisitStorage {
        val data = HashMap<String, String>()
        override fun freshVisitValue(key: String): String? = data[key]
        override fun setFreshVisitValue(key: String, value: String) { data[key] = value }
    }

    private class Failing : FreshVisitStorage {
        override fun freshVisitValue(key: String): String? = throw IllegalStateException("denied")
        override fun setFreshVisitValue(key: String, value: String): Unit = throw IllegalStateException("denied")
    }

    /**
     * Thread-safe, with a pause between a read and the caller's write so an
     * unlocked read-compare-write in `recordNewest` loses updates.
     */
    private class SlowStore : FreshVisitStorage {
        private val data = HashMap<String, String>()
        override fun freshVisitValue(key: String): String? {
            val v = synchronized(data) { data[key] }
            Thread.sleep(0, 200_000)
            return v
        }
        override fun setFreshVisitValue(key: String, value: String) { synchronized(data) { data[key] = value } }
    }

    private val pk = "a".repeat(64)
    private val key = FreshLastVisit.key(pk)

    // the visit mark

    @Test
    fun isNullOnAFirstVisit() {
        assertNull(FreshLastVisit().beginVisit(pk, Store()))
    }

    @Test
    fun readsThePreviousVisitOnce_andKeepsItForTheSession() {
        val s = Store().apply { data[key] = "1000" }
        val visit = FreshLastVisit()
        assertEquals(1000L, visit.beginVisit(pk, s))
        visit.recordNewest(2000, pk, s)
        // A refresh in the same session keeps the divider where it was.
        assertEquals(1000L, visit.beginVisit(pk, s))
        visit.resetSession()
        assertEquals(2000L, visit.beginVisit(pk, s))
    }

    @Test
    fun aFirstVisitStaysAFirstVisitForTheSession() {
        val s = Store()
        val visit = FreshLastVisit()
        assertNull(visit.beginVisit(pk, s))
        visit.recordNewest(2000, pk, s)
        assertNull(visit.beginVisit(pk, s))
    }

    @Test
    fun neverMovesTheStoredMarkBack() {
        val s = Store()
        val visit = FreshLastVisit()
        visit.recordNewest(2000, pk, s)
        visit.recordNewest(1500, pk, s)
        assertEquals("2000", s.data[key])
    }

    @Test
    fun ignoresGarbageAndFailingStorage() {
        val s = Store().apply { data[key] = "soon" }
        assertNull(FreshLastVisit().beginVisit(pk, s))
        assertNull(FreshLastVisit().beginVisit(pk, null))
        val visit = FreshLastVisit()
        assertNull(visit.beginVisit(pk, Failing()))
        visit.recordNewest(5, pk, Failing()) // does not throw
    }

    @Test
    fun concurrentRecords_leaveTheNewestStored() {
        val s = SlowStore()
        val visit = FreshLastVisit()
        val times = (1L..200L).shuffled()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(times.size)
        for (t in times) pool.execute { visit.recordNewest(t, pk, s); done.countDown() }
        done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals("200", s.freshVisitValue(key))
    }

    @Test
    fun outOfRangeStoredMark_readsAsNoMark_andIsReplaced() {
        for (raw in listOf("1e100", "9.3e18", Double.MAX_VALUE.toString())) {
            val s = Store().apply { data[key] = raw }
            assertNull(raw, FreshLastVisit().beginVisit(pk, s))
            FreshLastVisit().recordNewest(1000, pk, s)
            assertEquals(raw, "1000", s.data[key])
        }
    }

    @Test
    fun eachAccountHasItsOwnMark() {
        val s = Store()
        val other = "b".repeat(64)
        val visit = FreshLastVisit()
        visit.recordNewest(1000, pk, s)
        assertNull(visit.beginVisit(other, s))
        assertEquals(1000L, visit.beginVisit(pk, s))
    }

    // withDivider

    private data class Row(val key: String, val createdAt: Long, val box: Boolean) : FreshDividable {
        override val isSpecialCard get() = box
        override val dividerCreatedAt get() = createdAt
    }

    private fun row(id: String, t: Long, box: Boolean = false) = Row(if (box) "box:$id" else id, t, box)

    private fun keys(rows: List<FreshDividedRow<Row>>) = rows.map {
        when (it) {
            is FreshDividedRow.Row -> it.row.key
            is FreshDividedRow.Divider -> "divider"
        }
    }

    private val rows = listOf(row("a", 300), row("b", 250), row("r", 10, box = true), row("c", 200), row("d", 100))

    @Test
    fun goesBeforeTheFirstPostAlreadySeen_countingNewPostsNotPicks() {
        val out = FreshDivider.withDivider(rows, 200)
        assertEquals(listOf("a", "b", "box:r", "divider", "c", "d"), keys(out))
        assertEquals(FreshDividedRow.Divider(2), out[3])
    }

    @Test
    fun noneOnAFirstVisit_whenAllIsNew_orWhenNothingIs() {
        assertEquals(rows.map { it.key }, keys(FreshDivider.withDivider(rows, null)))
        assertFalse(FreshDivider.withDivider(rows, 50).any { it is FreshDividedRow.Divider })
        assertFalse(FreshDivider.withDivider(rows, 999).any { it is FreshDividedRow.Divider })
    }

    @Test
    fun aRecipeRowPickNeverPlacesTheDivider() {
        val picksFirst = listOf(row("r", 10, box = true), row("a", 300), row("c", 100))
        assertEquals(listOf("box:r", "a", "divider", "c"), keys(FreshDivider.withDivider(picksFirst, 200)))
    }

    @Test
    fun specialCardsAboveTheFirstSeenPost_withNothingNew_placeNoDivider() {
        val r = listOf(row("r", 10, box = true), row("c", 100), row("d", 50))
        assertEquals(listOf("box:r", "c", "d"), keys(FreshDivider.withDivider(r, 200)))
    }
}
