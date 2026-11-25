package cooking.zap.app.fresh

/**
 * Where the "new since your last visit" mark is kept. Device-local only;
 * nothing about viewing leaves the device (build spec §1). Either call may
 * throw: a failing store means no divider, nothing else.
 */
interface FreshVisitStorage {
    fun freshVisitValue(key: String): String?
    fun setFreshVisitValue(key: String, value: String)
}

/**
 * "New since your last visit", ported from iOS `FreshLastVisit.swift` (web
 * `src/lib/freshFeed/lastVisit.ts`).
 *
 * The stored value is the newest post time the reader was shown last time. A
 * visit reads it once ([beginVisit]) and keeps that value for the whole
 * session, so refreshes and tab switches don't move the divider; the newest
 * post seen now is stored for next time ([recordNewest]).
 *
 * The mark is per account (`fresh_last_visit_<pubkey>`). A session is this
 * object's lifetime: hold one per app run ([shared]).
 */
class FreshLastVisit {
    private val lock = Any()

    /** pubkey → this session's mark (present with null = first visit, read already). */
    private val sessionMarks = HashMap<String, Long?>()

    /** The previous visit's mark for this session (null on a first visit). */
    fun beginVisit(pubkey: String, storage: FreshVisitStorage?): Long? {
        synchronized(lock) {
            if (sessionMarks.containsKey(pubkey)) return sessionMarks[pubkey]
            val mark = try {
                storage?.freshVisitValue(key(pubkey))?.let(FreshPosts::wholeSeconds)
            } catch (_: Exception) {
                null
            }
            sessionMarks[pubkey] = mark
            return mark
        }
    }

    /**
     * Remember the newest post shown, for the next visit (never moves back).
     * The read, compare and write happen under the lock, so two concurrent
     * calls cannot leave the older value stored.
     */
    fun recordNewest(createdAt: Long, pubkey: String, storage: FreshVisitStorage?) {
        if (storage == null || createdAt <= 0) return
        val key = key(pubkey)
        synchronized(lock) {
            try {
                val cur = storage.freshVisitValue(key)?.let(FreshPosts::wholeSeconds)
                if (cur != null && cur >= createdAt) return
                storage.setFreshVisitValue(key, createdAt.toString())
            } catch (_: Exception) {
                // A failing store: no divider next time, nothing else.
            }
        }
    }

    /** Forget this session's marks (account wipe, tests). */
    fun resetSession() {
        synchronized(lock) { sessionMarks.clear() }
    }

    companion object {
        val shared = FreshLastVisit()

        fun key(pubkey: String): String = "fresh_last_visit_$pubkey"
    }
}

/**
 * A row the divider can be placed among: a post ([dividerCreatedAt]) or a
 * special card ([isSpecialCard], e.g. a recipe-box card), which never places it.
 */
interface FreshDividable {
    val isSpecialCard: Boolean
    val dividerCreatedAt: Long
}

sealed interface FreshDividedRow<out R> {
    data class Row<R>(val row: R) : FreshDividedRow<R>
    data class Divider(val newCount: Int) : FreshDividedRow<Nothing>
}

object FreshDivider {
    /**
     * Put the "caught up" divider before the first post (not a special card)
     * the reader already saw, with the number of new posts above it. No
     * divider on a first visit, when every post shown is new (the reader
     * hasn't reached the old ones yet), or when none is. A zero count means
     * no divider (≠ web, which checks only the divider's index).
     */
    fun <R : FreshDividable> withDivider(rows: List<R>, mark: Long?): List<FreshDividedRow<R>> {
        val plain: List<FreshDividedRow<R>> = rows.map { FreshDividedRow.Row(it) }
        if (mark == null) return plain
        val at = rows.indexOfFirst { !it.isSpecialCard && it.dividerCreatedAt <= mark }
        if (at <= 0) return plain
        val newCount = rows.subList(0, at).count { !it.isSpecialCard }
        if (newCount <= 0) return plain
        return plain.subList(0, at) + FreshDividedRow.Divider(newCount) + plain.subList(at, plain.size)
    }
}
