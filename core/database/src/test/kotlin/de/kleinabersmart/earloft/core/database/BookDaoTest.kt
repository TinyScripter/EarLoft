package de.kleinabersmart.earloft.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookDaoTest {

    private lateinit var db: EarLoftDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the next generation is never below one already stored`() = runTest {
        val dao = db.bookDao()
        val now = System.currentTimeMillis()
        assertTrue(dao.nextGeneration() >= now)

        // Die Uhr ging vor: Ein Eintrag trägt eine Generation aus der Zukunft.
        val future = now + 3_600_000
        dao.upsertBook(BookEntity(serverId = "local", remoteId = "a", title = "A", normTitle = "a", author = "", durationMs = 0, seenInGeneration = future))
        val next = dao.nextGeneration()
        assertEquals(future + 1, next)

        dao.deleteUnseen("local", next)
        assertEquals(null, dao.bookByRemote("local", "a"))
    }
}
