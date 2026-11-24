package de.kleinabersmart.earloft.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Listening time per entry and per day: added up, cleared per kind and replaced per subscription. */
@RunWith(RobolectricTestRunner::class)
class StatsDaoTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var dao: StatsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.statsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun stat(kind: BookKind, group: String, item: String, ms: Long, at: Long, title: String = item) = ListeningStatEntity(
        source = ListeningStatEntity.SOURCE_APP, kind = kind, groupKey = group, groupTitle = group, itemKey = item, itemTitle = title,
        listenedMs = ms, lastListenedAt = at,
    )

    private suspend fun stats() = dao.listenedSince(ListeningStatEntity.SOURCE_APP, 0).associateBy { it.itemKey }

    private suspend fun days() = dao.allDays().associate { (it.day to it.groupKey) to (it.listenedMs to it.savedMs) }

    @Test
    fun `listening adds up per entry and per day, with the latest title and time`() = runTest {
        dao.addListening(stat(BookKind.EPISODE, "feed", "e1", ms = 60_000, at = 1_000), day = 100, savedMs = 5_000)
        dao.addListening(stat(BookKind.EPISODE, "feed", "e1", ms = 30_000, at = 2_000, title = "e1 (renamed)"), day = 100, savedMs = 1_000)
        dao.addListening(stat(BookKind.EPISODE, "feed", "e1", ms = 10_000, at = 3_000), day = 101)
        dao.addListening(stat(BookKind.EPISODE, "feed", "e2", ms = 20_000, at = 4_000), day = 101)

        val e1 = stats().getValue("e1")
        assertEquals(100_000L, e1.listenedMs)
        assertEquals(3_000L, e1.lastListenedAt)
        assertEquals(20_000L, stats().getValue("e2").listenedMs)
        assertEquals(
            mapOf((100L to "feed") to (90_000L to 6_000L), (101L to "feed") to (30_000L to 0L)),
            days(),
        )
    }

    @Test
    fun `clearing one kind keeps the other`() = runTest {
        dao.addListening(stat(BookKind.EPISODE, "feed", "e1", ms = 60_000, at = 1), day = 100)
        dao.addListening(stat(BookKind.BOOK, "abs", "b1", ms = 30_000, at = 1), day = 100)

        dao.clear(BookKind.EPISODE)

        assertEquals(setOf("b1"), stats().keys)
        assertEquals(setOf(100L to "abs"), days().keys)
    }

    @Test
    fun `switching a subscription's source replaces exactly its numbers`() = runTest {
        dao.addListening(stat(BookKind.EPISODE, "old-feed", "e1", ms = 60_000, at = 1), day = 100)
        dao.addListening(stat(BookKind.EPISODE, "other", "x1", ms = 10_000, at = 1), day = 100)

        dao.replaceGroups(
            listOf("old-feed"),
            listOf(stat(BookKind.EPISODE, "new-feed", "e1", ms = 60_000, at = 1)),
            listOf(ListeningDayEntity(100, BookKind.EPISODE, "new-feed", 60_000)),
        )

        assertEquals(setOf("new-feed", "other"), stats().values.mapTo(HashSet()) { it.groupKey })
        assertEquals(setOf(100L to "new-feed", 100L to "other"), days().keys)
        assertTrue(dao.statsOfGroups(listOf("old-feed")).isEmpty())
    }
}
