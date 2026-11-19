package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpisodeQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun episode(db: EarLoftDatabase, guid: String) = db.bookDao().upsertBook(
        BookEntity(serverId = "feed-1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 1_000),
    )

    @Test
    fun `order and hidden episodes survive new book ids`() = runTest {
        val a = episode(db, "a")
        val b = episode(db, "b,mit Komma")
        val c = episode(db, "c")
        val queue = EpisodeQueue(context, db.bookDao())
        queue.append(a)
        queue.moveToFront(b)
        queue.remove(c)
        assertEquals(listOf(b, a), queue.order.first())
        assertEquals(setOf(c), queue.hidden.first())

        // New device: the same (backed-up) file, the episodes come with different ids and only gradually.
        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val restored = EpisodeQueue(context, newDevice.bookDao())
            assertEquals(emptyList<Long>(), restored.order.first())
            episode(newDevice, "andere")
            val newA = episode(newDevice, "a")
            assertEquals(listOf(newA), restored.order.first())
            val newC = episode(newDevice, "c")
            val newB = episode(newDevice, "b,mit Komma")
            assertEquals(listOf(newB, newA), restored.order.first())
            assertEquals(setOf(newC), restored.hidden.first())
        } finally {
            newDevice.close()
        }
    }
}
