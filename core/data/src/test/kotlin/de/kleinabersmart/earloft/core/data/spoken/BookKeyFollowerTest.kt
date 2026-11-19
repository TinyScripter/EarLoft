package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import de.kleinabersmart.earloft.core.data.backup.MusicBackup
import de.kleinabersmart.earloft.core.data.backup.RestoreNotice
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookKeyFollowerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: EarLoftDatabase
    private lateinit var user: UserDatabase
    private lateinit var follower: BookKeyFollower
    private val renamed = mutableListOf<Map<String, String>>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
        val backup = UserDataBackup(
            context = context,
            database = db,
            userDao = user.userDataDao(),
            sourceDao = db.sourceDao(),
            bookDao = db.bookDao(),
            podcastDao = db.podcastDao(),
            statsDao = db.statsDao(),
            music = MusicBackup(db.backupDao(), db.playlistDao(), db.syncDao()),
            restoreNotice = RestoreNotice(context, { error("nicht gebraucht") }, { error("nicht gebraucht") }),
            registry = Lazy<DataSourceRegistry> { error("nicht gebraucht") },
            ardBooks = { error("nicht gebraucht") },
            scope = TestScope(),
        )
        val renamer = object : BookKeyRenamer {
            override suspend fun renameBookKeys(renames: Map<String, String>) {
                renamed += renames
            }
        }
        follower = BookKeyFollower(db.statsDao(), { backup }, { setOf(renamer) })
    }

    @After
    fun tearDown() {
        db.close()
        user.close()
    }

    @Test
    fun `a book under a new key keeps queue, statistics and drops the saved progress of the old key`() = runTest {
        listOf("group:alt" to 60_000L, "group:neu" to 5_000L, "group:andere" to 1_000L).forEach { (key, ms) ->
            db.statsDao().addListening(
                ListeningStatEntity(source = ListeningStatEntity.SOURCE_APP, kind = BookKind.BOOK, groupKey = ARD_BOOK_SERVER_ID,
                    groupTitle = "ARD Sounds", itemKey = key, itemTitle = key, listenedMs = ms, lastListenedAt = ms),
                day = 20_000,
            )
        }
        user.openHelper.writableDatabase.execSQL(
            "INSERT INTO book_progress (serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty) " +
                "VALUES ('$ARD_BOOK_SERVER_ID', 'group:alt', 60000, 600000, 0, 1.0, 100, 0)",
        )

        follower.moved(ARD_BOOK_SERVER_ID, mapOf("group:alt" to "group:neu"))

        // Both entries now count together under the new key; the days stay.
        assertEquals(
            mapOf("group:neu" to 65_000L, "group:andere" to 1_000L),
            db.statsDao().all().associate { it.itemKey to it.listenedMs },
        )
        assertEquals(66_000L, db.statsDao().allDays().sumOf { it.listenedMs })
        assertEquals(
            listOf(mapOf(BookKeys.encode(ARD_BOOK_SERVER_ID, "group:alt") to BookKeys.encode(ARD_BOOK_SERVER_ID, "group:neu"))),
            renamed,
        )
        assertEquals(0, user.openHelper.readableDatabase.query("SELECT * FROM book_progress").use { it.count })
    }
}
