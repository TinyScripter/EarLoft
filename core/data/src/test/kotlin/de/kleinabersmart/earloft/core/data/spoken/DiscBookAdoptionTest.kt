package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Disc folders scanned as books of their own before review5 P35 hand their progress and bookmarks to the merged book. */
@RunWith(RobolectricTestRunner::class)
class DiscBookAdoptionTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), EarLoftDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.bookDao()

    @After
    fun tearDown() = db.close()

    private suspend fun book(key: String, vararg files: Pair<String, Long>): Pair<Long, List<BookTrackEntity>> {
        val bookId = dao.upsertBook(
            BookEntity(
                serverId = LOCAL_BOOK_SERVER_ID, kind = BookKind.BOOK, remoteId = key, title = key, normTitle = key, author = "",
                durationMs = files.sumOf { it.second },
            ),
        )
        var start = 0L
        val tracks = files.mapIndexed { index, (uri, duration) ->
            BookTrackEntity(bookId, index, uri, start, duration, "audio/mpeg").also { start += duration }
        }
        dao.replaceDetails(bookId, tracks, emptyList())
        return bookId to tracks
    }

    @Test
    fun `the merged book continues where the newest disc was and keeps all bookmarks`() = runTest {
        val (cd1, _) = book("Audiobooks/Novel/CD 1/", "u1" to 3_000L, "u2" to 3_000L)
        val (cd2, _) = book("Audiobooks/Novel/CD 2/", "u3" to 4_000L)
        // A book of its own beside the discs: not part of the merged book.
        val (other, _) = book("Audiobooks/Novel/Extras/", "x1" to 1_000L)
        dao.upsertProgress(BookProgressEntity(cd1, 6_000, 6_000, finished = true, updatedAt = 10, finishedAt = 10))
        dao.upsertProgress(BookProgressEntity(cd2, 2_500, 4_000, speed = 1.3f, updatedAt = 20))
        dao.upsertProgress(BookProgressEntity(other, 500, 1_000, updatedAt = 30))
        dao.insertBookmark(BookmarkEntity(bookId = cd1, positionMs = 1_000, title = "One", createdAt = 1, updatedAt = 1))
        dao.insertBookmark(BookmarkEntity(bookId = cd2, positionMs = 2_000, title = "Two", createdAt = 2, updatedAt = 2))
        val (merged, tracks) = book("Audiobooks/Novel/", "u1" to 3_000L, "u2" to 3_000L, "u3" to 4_000L)

        assertEquals(2, dao.adoptDiscBooks(merged, "Audiobooks/Novel/", tracks, 10_000))

        val progress = dao.progress(merged)!!
        assertEquals(8_500L, progress.positionMs)
        assertEquals(10_000L, progress.durationMs)
        assertEquals(1.3f, progress.speed)
        assertFalse(progress.finished)
        assertEquals(listOf(1_000L to "One", 8_000L to "Two"), dao.bookmarksOf(listOf(merged)).map { it.positionMs to it.title }.sortedBy { it.first })
    }

    @Test
    fun `a finished first disc continues at the start of the next one`() = runTest {
        val (cd1, _) = book("Audiobooks/Novel/CD 1/", "u1" to 3_000L)
        book("Audiobooks/Novel/CD 2/", "u2" to 4_000L)
        dao.upsertProgress(BookProgressEntity(cd1, 3_000, 3_000, finished = true, updatedAt = 10, finishedAt = 10))
        val (merged, tracks) = book("Audiobooks/Novel/", "u1" to 3_000L, "u2" to 4_000L)

        dao.adoptDiscBooks(merged, "Audiobooks/Novel/", tracks, 7_000)

        val progress = dao.progress(merged)!!
        assertEquals(3_000L, progress.positionMs)
        assertFalse(progress.finished)
    }
}
