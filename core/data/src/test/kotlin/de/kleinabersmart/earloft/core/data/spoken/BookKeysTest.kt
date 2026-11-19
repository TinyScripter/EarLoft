package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BookKeysTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val marker = File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER)
    private lateinit var db: EarLoftDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        marker.delete()
    }

    private suspend fun episode(db: EarLoftDatabase, guid: String) = db.bookDao().upsertBook(
        BookEntity(serverId = "feed-1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 1_000),
    )

    @Test
    fun `a key finds the same episode on another device`() = runTest {
        val key = BookKeys(context, db.bookDao()).keyOf(episode(db, "episode-7"))!!

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val keys = BookKeys(context, newDevice.bookDao())
            assertNull(keys.idOf(key))
            episode(newDevice, "other")
            val id = episode(newDevice, "episode-7")
            assertEquals(id, keys.idOf(key))
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `old ids count only if this install did not create the database`() = runTest {
        val id = episode(db, "episode-1")
        val keys = BookKeys(context, db.bookDao())
        assertEquals(keys.keyOf(id), keys.keyOfLegacyId(id))

        marker.createNewFile()
        assertNull(keys.keyOfLegacyId(id))
    }
}
