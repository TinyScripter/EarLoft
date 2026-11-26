package de.kleinabersmart.earloft.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.WearBookEntity
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.flow.first
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

        // The clock was ahead: an entry carries a generation from the future.
        val future = now + 3_600_000
        dao.upsertBook(BookEntity(serverId = "local", remoteId = "a", title = "A", normTitle = "a", author = "", durationMs = 0, seenInGeneration = future))
        val next = dao.nextGeneration()
        assertEquals(future + 1, next)

        dao.deleteUnseen("local", next)
        assertEquals(null, dao.bookByRemote("local", "a"))
    }

    @Test
    fun `books from ARD Sounds and from the device go to the watch, those of a missing server not`() = runTest {
        val dao = db.bookDao()
        val ard = dao.upsertBook(BookEntity(serverId = ARD_BOOK_SERVER_ID, remoteId = "group:x", title = "A", normTitle = "a", author = "", durationMs = 0))
        val local = dao.upsertBook(BookEntity(serverId = "local-books", remoteId = "b", title = "B", normTitle = "b", author = "", durationMs = 0))
        val orphan = dao.upsertBook(BookEntity(serverId = "abs-gone", remoteId = "c", title = "C", normTitle = "c", author = "", durationMs = 0))
        listOf(ard, local, orphan).forEachIndexed { index, id -> db.wearDao().insertBook(WearBookEntity(id, addedAt = index.toLong())) }

        assertEquals(listOf(ard, local), db.wearDao().selectedSpokenIds())
    }

    @Test
    fun `the library has books exactly when the list of books has some`() = runTest {
        val dao = db.bookDao()
        assertEquals(false, dao.observeHasBooks().first())

        // An episode is in the same table, but not in the list of books.
        dao.upsertBook(BookEntity(serverId = "feed", kind = BookKind.EPISODE, remoteId = "e", title = "E", normTitle = "e", author = "", durationMs = 0))
        assertEquals(dao.observeBooks().first().isNotEmpty(), dao.observeHasBooks().first())

        dao.upsertBook(BookEntity(serverId = "local-books", remoteId = "b", title = "B", normTitle = "b", author = "", durationMs = 0))
        assertEquals(true, dao.observeHasBooks().first())
        assertEquals(true, dao.observeBooks().first().isNotEmpty())
    }
}
