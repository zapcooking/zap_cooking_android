package cooking.zap.app.lazarus

import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Lazarus spec's Tier-1 conformance vectors, ported from the reference
 * implementation's suites via zapcooking/frontend#753 (recovery.test.ts,
 * private-items.test.ts): ranking (clobber detection, episodes, settling),
 * delta identity, drafts, grouping, and private-item sizing against REAL
 * NIP-44/NIP-04 ciphertexts (LazarusCryptoFixtures, generated with
 * nostr-tools). The relay-I/O layer is exercised against fakes here and
 * against live sockets on-device.
 */
class LazarusCoreTest {

    private var counter = 0

    private fun makeEvent(
        createdAt: Long = 1,
        kind: Int = 3,
        tags: List<List<String>> = emptyList(),
        content: String = ""
    ): NostrEvent {
        counter += 1
        return NostrEvent(
            id = "test-event-$counter".padEnd(64, '0'),
            pubkey = "test-pubkey",
            created_at = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = "test-sig"
        )
    }

    private fun followListEvent(count: Int, createdAt: Long, content: String = "") =
        makeEvent(
            createdAt = createdAt,
            tags = (0 until count).map { listOf("p", "pk$it") },
            content = content
        )

    private fun muteListEvent(count: Int, createdAt: Long) = makeEvent(
        kind = 10000,
        createdAt = createdAt,
        tags = (0 until count).map { i -> listOf(MUTE_TAG_TYPES[i % MUTE_TAG_TYPES.size], "item$i") }
    )

    private fun ranked(vararg counts: Int): Pair<List<NostrEvent>, LazarusScanResult> {
        val events = counts.mapIndexed { i, c -> followListEvent(c, 1000L + i) }
        val scan = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            events.map { LazarusTaggedEvent(it, "wss://a") }
        )
        return events to scan
    }

    private fun tag(count: Int, createdAt: Long) = makeEvent(
        createdAt = createdAt,
        tags = (0 until count).map { listOf("p", it.toString(16).padStart(64, '0')) }
    )



    // ---- registry ----

    @Test
    fun `pins tier 1 kinds required for conformance`() {
        assertEquals(1, getLazarusKindProfile(3)!!.tier)
        assertEquals(1, getLazarusKindProfile(10000)!!.tier)
    }

    @Test
    fun `flags kind 10044 as meaningful-empty with no ranking`() {
        val profile = getLazarusKindProfile(10044)!!
        assertTrue(profile.meaningfulEmpty)
        assertEquals(LazarusRanking.INTENT, profile.ranking)
    }

    @Test
    fun `never returns profiles for unregistered kinds`() {
        assertNull(getLazarusKindProfile(30078))
        assertNull(getLazarusKindProfile(1))
    }

    // ---- rankLazarusCandidates ----

    @Test
    fun `ranks count kinds by item count, not recency`() {
        val olderBigger = followListEvent(120, 1000)
        val newerSmaller = followListEvent(2, 2000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(
                LazarusTaggedEvent(newerSmaller, "wss://a"),
                LazarusTaggedEvent(olderBigger, "wss://b")
            )
        )
        assertEquals(olderBigger.id, result.candidates[0].event.id)
        // current is still the newest version the scan saw
        assertEquals(newerSmaller.id, result.current?.event?.id)
        assertEquals(olderBigger.id, result.recommended?.event?.id)
        assertTrue(result.recommended?.isRecommended == true)
    }

    @Test
    fun `never recommends empty candidates even when they are newest`() {
        val tombstone = followListEvent(0, 3000)
        val healthy = followListEvent(50, 1000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(
                LazarusTaggedEvent(tombstone, "wss://a"),
                LazarusTaggedEvent(healthy, "wss://b")
            )
        )
        assertEquals(tombstone.id, result.current?.event?.id)
        assertEquals(healthy.id, result.recommended?.event?.id)
    }

    @Test
    fun `recommends nothing when current is already the best`() {
        // The biggest version is the NEWEST one (TS vector: 80@3000, 10@1000).
        val (_, result) = ranked(10, 80)
        assertNull(result.recommended)
    }

    @Test
    fun `keeps the current version when an older one is only slightly bigger`() {
        // A few unfollows over time is curation, not a clobber
        val (_, result) = ranked(1102, 1094)
        assertNull(result.recommended)
    }

    @Test
    fun `recommends an older version when the current one lost a large share of it`() {
        val beforeClobber = followListEvent(1945, 1000)
        val current = followListEvent(1094, 2000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(
                LazarusTaggedEvent(beforeClobber, "wss://a"),
                LazarusTaggedEvent(current, "wss://a")
            )
        )
        assertEquals(beforeClobber.id, result.recommended?.event?.id)
    }

    @Test
    fun `does not recommend over a couple of items on a small list`() {
        val (_, result) = ranked(6, 4)
        assertNull(result.recommended)
    }

    @Test
    fun `keeps the current version when the list shrank gradually, however far`() {
        // Each step loses about a tenth: curation, even though 2000 to 1200 is 40%
        val (_, result) = ranked(2000, 1800, 1600, 1400, 1200)
        assertNull(result.recommended)
    }

    @Test
    fun `recommends the version before the latest clobber, not an older peak`() {
        // Slow curation from 3000 to 1945, then a clobber to empty and a partial rebuild
        val (events, result) = ranked(3000, 2600, 2250, 1945, 0, 500, 1094)
        assertEquals(events[3].id, result.recommended?.event?.id)
    }

    @Test
    fun `treats a clobber the list has been edited on for a week as settled`() {
        val day = 24 * 3600L
        val versions = listOf(
            followListEvent(1945, day),
            followListEvent(1114, day + 60), // clobbered
            followListEvent(1112, 2 * 2 * day),
            followListEvent(1110, 3 * 2 * day),
            followListEvent(1105, 4 * 2 * day),
            followListEvent(1100, 5 * 2 * day),
            followListEvent(1094, 6 * 2 * day)
        )
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            versions.map { LazarusTaggedEvent(it, "wss://a") }
        )
        assertNull(result.recommended)
    }

    @Test
    fun `still recommends when the edits since a clobber all came within a week`() {
        val hour = 3600L
        val versions = listOf(
            followListEvent(1945, hour),
            followListEvent(1114, 2 * hour), // clobbered
            followListEvent(1112, 3 * hour),
            followListEvent(1110, 4 * hour),
            followListEvent(1105, 5 * hour),
            followListEvent(1100, 6 * hour),
            followListEvent(1094, 7 * hour)
        )
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            versions.map { LazarusTaggedEvent(it, "wss://a") }
        )
        assertEquals(versions[0].id, result.recommended?.event?.id)
    }

    @Test
    fun `treats back-to-back drops as one clobber`() {
        val (events, result) = ranked(500, 3, 0)
        assertEquals(events[0].id, result.recommended?.event?.id)
    }

    @Test
    fun `points at the fullest version before a clobber that bounced`() {
        // Clobbered, partly restored, and clobbered again within hours
        val hour = 3600L
        val day = 24 * hour
        val events = listOf(
            followListEvent(1945, 10 * hour),
            followListEvent(1114, 11 * hour),
            followListEvent(1660, 12 * hour),
            followListEvent(1114, 13 * hour),
            followListEvent(1100, 5 * day),
            followListEvent(1094, 90 * day)
        )
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            events.map { LazarusTaggedEvent(it, "wss://a") }
        )
        assertEquals(events[0].id, result.recommended?.event?.id)
    }

    @Test
    fun `does not reach back to an unrelated clobber weeks earlier`() {
        val day = 24 * 3600L
        val events = listOf(
            followListEvent(3000, day),
            followListEvent(2000, day + 60), // clobbered
            followListEvent(2950, 2 * day), // restored the next day
            followListEvent(2600, 20 * day), // then curated down over two months
            followListEvent(2250, 40 * day),
            followListEvent(1945, 60 * day),
            followListEvent(1114, 60 * day + 60), // clobbered again
            followListEvent(1100, 90 * day)
        )
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            events.map { LazarusTaggedEvent(it, "wss://a") }
        )
        assertEquals(events[5].id, result.recommended?.event?.id)
    }

    @Test
    fun `recommends nothing for meaningful-empty kinds and requires intent`() {
        val keys = makeEvent(kind = 10044, createdAt = 1000, tags = listOf(listOf("p", "encryption-pubkey-1")))
        val emptied = makeEvent(kind = 10044, createdAt = 2000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(10044)!!,
            listOf(
                LazarusTaggedEvent(emptied, "wss://a"),
                LazarusTaggedEvent(keys, "wss://b")
            )
        )
        assertTrue(result.requiresIntentConfirmation)
        assertNull(result.recommended)
        // still offered, in recency order, not labeled damage
        assertEquals(2, result.candidates.size)
        assertEquals(emptied.id, result.candidates[0].event.id)
    }

    @Test
    fun `dedupes by event id and accumulates found-on relays`() {
        val shared = followListEvent(5, 1000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(
                LazarusTaggedEvent(shared, "wss://a"),
                LazarusTaggedEvent(shared, "wss://b"),
                LazarusTaggedEvent(shared, "wss://a")
            )
        )
        assertEquals(1, result.candidates.size)
        assertEquals(listOf("wss://a", "wss://b"), result.candidates[0].foundOn)
    }

    @Test
    fun `counts mute lists across all NIP-51 tag types`() {
        val mutes = muteListEvent(8, 1000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(10000)!!,
            listOf(LazarusTaggedEvent(mutes, "wss://a"))
        )
        assertEquals(8, result.candidates[0].itemCount.count)
    }

    @Test
    fun `marks encrypted-content candidates as partially counted`() {
        val withPrivate = followListEvent(3, 1000, LazarusCryptoFixtures.NIP44_CASES[2].nip44Content)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(LazarusTaggedEvent(withPrivate, "wss://a"))
        )
        assertTrue(result.candidates[0].itemCount.partial)
    }

    @Test
    fun `does not treat legacy relay JSON in a follow list as private items`() {
        val withRelays = followListEvent(3, 1000, "{\"wss://relay\": {\"read\": true}}")
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(LazarusTaggedEvent(withRelays, "wss://a"))
        )
        assertEquals(3, result.candidates[0].itemCount.count)
        assertFalse(result.candidates[0].itemCount.partial)
    }

    // ---- computeLazarusDelta ----

    @Test
    fun `computes additions, removals, and direction`() {
        val current = makeEvent(tags = listOf(listOf("p", "a"), listOf("p", "b")))
        val chosen = makeEvent(tags = listOf(listOf("p", "b"), listOf("p", "c")))
        val delta = computeLazarusDelta(chosen, current)
        assertEquals(1, delta.addedCount)
        assertEquals(1, delta.removedCount)
        assertTrue(delta.grows)
        assertFalse(delta.shrinks)
    }

    @Test
    fun `flags a shrink for separate confirmation`() {
        val current = makeEvent(tags = listOf(listOf("p", "a"), listOf("p", "b"), listOf("p", "c")))
        val chosen = makeEvent(tags = listOf(listOf("p", "a")))
        val delta = computeLazarusDelta(chosen, current)
        assertTrue(delta.shrinks)
    }

    @Test
    fun `treats a follow whose relay hint or petname changed as the same item`() {
        val current = makeEvent(tags = listOf(listOf("p", "a"), listOf("p", "b", "wss://old")))
        val chosen = makeEvent(tags = listOf(listOf("p", "a", "wss://new", "alice"), listOf("p", "b")))
        val delta = computeLazarusDelta(chosen, current)
        assertEquals(0, delta.addedCount)
        assertEquals(0, delta.removedCount)
    }

    @Test
    fun `counts a changed read-write marker on relay lists`() {
        val current = makeEvent(kind = 10002, tags = listOf(listOf("r", "wss://a", "read")))
        val chosen = makeEvent(kind = 10002, tags = listOf(listOf("r", "wss://a", "write")))
        val delta = computeLazarusDelta(chosen, current)
        assertEquals(1, delta.addedCount)
        assertEquals(1, delta.removedCount)
    }

    // ---- computeLazarusProfileChanges ----

    @Test
    fun `lists only the profile fields that change`() {
        fun profileEvent(content: String) = makeEvent(kind = 0, createdAt = 1, content = content)
        val current = profileEvent("{\"name\":\"clobbered\",\"about\":\"same\"}")
        val chosen = profileEvent("{\"name\":\"Daniel\",\"about\":\"same\",\"picture\":\"https://pic\"}")
        val changes = computeLazarusProfileChanges(chosen, current)
        assertEquals(
            listOf(
                LazarusProfileChange("name", "clobbered", "Daniel"),
                LazarusProfileChange("picture", null, "https://pic")
            ),
            changes
        )
    }

    @Test
    fun `compares the union of metadata keys, not a whitelist`() {
        // Upstream Copilot finding 6: client-specific fields must not be
        // silently replaceable by a restore.
        fun profileEvent(content: String) = makeEvent(kind = 0, createdAt = 1, content = content)
        val current = profileEvent("{\"name\":\"a\",\"bot\":\"true\"}")
        val chosen = profileEvent("{\"name\":\"a\",\"bot\":\"false\",\"pronouns\":\"they\"}")
        val changes = computeLazarusProfileChanges(chosen, current)
        assertEquals(listOf("bot", "pronouns"), changes.map { it.field })
    }

    // ---- buildLazarusRecoveryDraft ----

    @Test
    fun `copies the candidate verbatim with a fresh timestamp`() {
        val chosen = followListEvent(4, 999, "{\"wss://relay\": {\"read\": true}}")
        val draft = buildLazarusRecoveryDraft(chosen, now = 1234567890)
        assertEquals(3, draft.kind)
        assertEquals(1234567890, draft.created_at)
        assertEquals(chosen.content, draft.content)
        assertEquals(chosen.tags, draft.tags)
    }

    @Test
    fun `dates the draft after the version it replaces, even one from the future`() {
        val chosen = followListEvent(4, 999)
        val current = followListEvent(1, 1234568490) // ten minutes ahead of now
        val draft = buildLazarusRecoveryDraft(chosen, current = current, now = 1234567890)
        assertEquals(1234568491, draft.created_at)
    }

    // ---- sortLazarusCandidates ----

    @Test
    fun `sorts newest first by date and largest first by size`() {
        val small = followListEvent(10, 3000)
        val big = followListEvent(500, 1000)
        val bigNewer = followListEvent(500, 2000)
        val result = rankLazarusCandidates(
            getLazarusKindProfile(3)!!,
            listOf(small, big, bigNewer).map { LazarusTaggedEvent(it, "wss://a") }
        )
        assertEquals(
            listOf(small.id, bigNewer.id, big.id),
            sortLazarusCandidates(result.candidates, LazarusSortOrder.DATE).map { it.event.id }
        )
        assertEquals(
            listOf(bigNewer.id, big.id, small.id),
            sortLazarusCandidates(result.candidates, LazarusSortOrder.SIZE).map { it.event.id }
        )
    }

    // ---- groupLazarusCandidates ----

    private fun shapeOf(items: List<LazarusListItem>): List<String> = items.map { item ->
        when (item) {
            is LazarusListItem.Version -> item.candidate.event.id
            is LazarusListItem.Group -> item.candidates.joinToString("|") { it.event.id }
        }
    }

    @Test
    fun `folds a run of small edits and keeps the current version on its own row`() {
        val (events, scan) = ranked(1100, 1101, 1099, 1098, 1097, 1096, 1095, 1094)
        val shape = shapeOf(groupLazarusCandidates(scan, getLazarusKindProfile(3)!!))
        assertEquals(
            listOf(events[7].id, events.slice(0..6).reversed().joinToString("|") { it.id }),
            shape
        )
    }

    @Test
    fun `folds a clobber into its own group, apart from the curation around it`() {
        val (events, scan) = ranked(2000, 1990, 1980, 1945, 1114, 1110, 1100, 1094)
        val items = groupLazarusCandidates(scan, getLazarusKindProfile(3)!!)
        assertEquals(
            listOf(
                events[7].id,
                "${events[6].id}|${events[5].id}",
                "${events[4].id}|${events[3].id}",
                "${events[2].id}|${events[1].id}|${events[0].id}"
            ),
            shapeOf(items)
        )
        assertEquals(listOf(false, false, true, false), items.map { it is LazarusListItem.Group && it.clobbered })
        assertEquals(events[3].id, scan.recommended?.event?.id)
    }

    @Test
    fun `keeps empty versions on their own rows`() {
        val (events, scan) = ranked(500, 490, 0, 480, 470, 460)
        assertEquals(
            listOf(
                events[5].id,
                "${events[4].id}|${events[3].id}",
                events[2].id,
                events[1].id,
                events[0].id
            ),
            shapeOf(groupLazarusCandidates(scan, getLazarusKindProfile(3)!!))
        )
    }

    @Test
    fun `can leave out past empty versions, but never an empty current one`() {
        val (events, scan) = ranked(500, 490, 0, 480, 470, 460)
        val shape = shapeOf(groupLazarusCandidates(scan, getLazarusKindProfile(3)!!, hidePastEmpty = true))
        assertEquals(
            listOf(events[5].id, "${events[4].id}|${events[3].id}", events[1].id, events[0].id),
            shape
        )
        val (emptiedEvents, emptiedScan) = ranked(300, 0)
        assertEquals(
            listOf(emptiedEvents[1].id, emptiedEvents[0].id),
            shapeOf(groupLazarusCandidates(emptiedScan, getLazarusKindProfile(3)!!, hidePastEmpty = true))
        )
    }

    @Test
    fun `keeps empty versions of meaningful-empty kinds, where empty is a valid option`() {
        val events = listOf(
            makeEvent(kind = 10044, createdAt = 1000),
            makeEvent(kind = 10044, createdAt = 1001, tags = listOf(listOf("p", "a".repeat(64))))
        )
        val scan = rankLazarusCandidates(
            getLazarusKindProfile(10044)!!,
            events.map { LazarusTaggedEvent(it, "wss://a") }
        )
        val items = groupLazarusCandidates(scan, getLazarusKindProfile(10044)!!, hidePastEmpty = true)
        assertEquals(2, items.size)
    }

    @Test
    fun `does not group kinds where any two versions can differ`() {
        val events = (1000L..1002L).map { makeEvent(kind = 0, createdAt = it, content = "{\"name\":\"a\"}") }
        val scan = rankLazarusCandidates(
            getLazarusKindProfile(0)!!,
            events.map { LazarusTaggedEvent(it, "wss://a") }
        )
        val items = groupLazarusCandidates(scan, getLazarusKindProfile(0)!!)
        assertTrue(items.all { it is LazarusListItem.Version })
    }

    // ---- scanLazarusKind with a fake source ----

    @Test
    fun `reports relays that answered separately from relays queried`() {
        val healthy = followListEvent(30, 1000)
        val source = object : LazarusRelaySource {
            override suspend fun fetchVersions(kind: Int, pubkey: String, cursors: Map<String, Long>?): LazarusFetchPage =
                LazarusFetchPage(
                    tagged = listOf(
                        LazarusTaggedEvent(healthy, "wss://alive"),
                        LazarusTaggedEvent(healthy, "wss://mirror")
                    ),
                    queriedRelays = listOf("wss://alive", "wss://dead", "wss://mirror"),
                    respondingRelays = listOf("wss://alive", "wss://mirror")
                )
        }
        val result = kotlinx.coroutines.runBlocking {
            scanLazarusKind(3, "test-pubkey", source)
        }
        assertEquals(listOf("wss://alive", "wss://mirror"), result.respondingRelays)
        assertEquals(listOf("wss://alive", "wss://dead", "wss://mirror"), result.queriedRelays)
        assertEquals(1, result.candidates.size)
        assertEquals(listOf("wss://alive", "wss://mirror"), result.candidates[0].foundOn)
    }

    // ---- private items: conformance vectors against real ciphertexts ----

    @Test
    fun `brackets the real count of a NIP-44 list across sizes`() {
        for (fixture in LazarusCryptoFixtures.NIP44_CASES) {
            val lengths = getPlaintextLengthRange(fixture.nip44Content)!!
            assertTrue(lengths.min <= fixture.plainText.length)
            assertTrue(lengths.max >= fixture.plainText.length)
            val estimate = estimatePrivateItems(fixture.nip44Content)!!
            assertTrue("min ${estimate.min} <= ${fixture.n}", estimate.min <= fixture.n)
            assertTrue("max ${estimate.max} >= ${fixture.n}", estimate.max >= fixture.n)
        }
    }

    @Test
    fun `tells an emptied list apart from a full one`() {
        val emptied = estimatePrivateItems(LazarusCryptoFixtures.NIP44_CASES[1].nip44Content)!! // n=1
        val full = estimatePrivateItems(LazarusCryptoFixtures.NIP44_CASES.last().nip44Content)!! // n=593
        assertTrue(full.min > emptied.max)
    }

    @Test
    fun `brackets the real count of a NIP-04 list`() {
        val lengths = getPlaintextLengthRange(LazarusCryptoFixtures.NIP04_CONTENT)!!
        assertTrue(lengths.min <= LazarusCryptoFixtures.NIP04_PLAIN.length)
        assertTrue(lengths.max >= LazarusCryptoFixtures.NIP04_PLAIN.length)
        val estimate = estimatePrivateItems(LazarusCryptoFixtures.NIP04_CONTENT)!!
        assertTrue(estimate.min <= 40)
        assertTrue(estimate.max >= 40)
    }

    @Test
    fun `returns null for payloads that are not a valid size`() {
        assertNull(estimatePrivateItems("A".repeat(133)))
        assertNull(estimatePrivateItems("plain text"))
    }

    @Test
    fun `recognizes NIP-44 and NIP-04 payloads and rejects plain content`() {
        assertEquals(LazarusEncryption.NIP44, getContentEncryption(LazarusCryptoFixtures.NIP44_CASES[3].nip44Content))
        assertEquals(LazarusEncryption.NIP04, getContentEncryption(LazarusCryptoFixtures.NIP04_CONTENT))
        assertNull(getContentEncryption(""))
        assertNull(getContentEncryption("{\"wss://relay.damus.io\":{\"read\":true,\"write\":true}}"))
        assertNull(getContentEncryption("encrypted-private-items"))
        assertNull(getContentEncryption("abc?iv=not base64!"))
    }

    @Test
    fun `parses a decrypted tag list and rejects anything else`() {
        val plain = "[[\"p\",\"a\"],[\"word\",\"spam\"]]"
        assertEquals(listOf(listOf("p", "a"), listOf("word", "spam")), parsePrivateTags(plain))
        assertNull(parsePrivateTags("{\"not\":\"tags\"}"))
        assertNull(parsePrivateTags("not json"))
    }

    @Test
    fun `counts only the item tag types asked for`() {
        val tags = listOf(
            listOf("p", "a"),
            listOf("word", "spam"),
            listOf("t", "nsfw"),
            listOf("e", "x"),
            listOf("alt", "ignored")
        )
        assertEquals(4, countItemTags(tags, listOf("p", "word", "t", "e")))
    }

    @Test
    fun `re-ranking with decrypted private tags replaces estimates exactly`() {
        // A private-only mute list: no public tags, so the estimate carries the
        // ranking until the plaintext lands, then the count is exact.
        val fixture = LazarusCryptoFixtures.NIP44_CASES[2] // n = 3
        val event = makeEvent(kind = 10000, createdAt = 1000) // no public tags
            .copy(content = fixture.nip44Content)
        val profile = getLazarusKindProfile(10000)!!
        val scan = rankLazarusCandidates(profile, listOf(LazarusTaggedEvent(event, "wss://a")))
        val estimate = scan.candidates[0].itemCount
        assertTrue(estimate.partial)
        assertNotNull(estimate.privateEstimate)
        assertTrue(getLazarusItemRange(estimate).min <= fixture.n + 0)
        // Now decrypt (the plaintext is the fixture's) and re-rank.
        val plain = parsePrivateTags(fixture.plainText)!!
        val reranked = applyLazarusPrivateTags(
            profile, scan,
            mapOf(event.id to plain)
        )
        val exact = reranked.candidates[0].itemCount
        assertFalse(exact.partial)
        assertEquals(fixture.n, exact.privateCount)
        assertEquals(fixture.n, getLazarusItemRange(exact).max)
    }

    companion object {
        private val MUTE_TAG_TYPES = listOf("p", "word", "t", "e")
    }
}
