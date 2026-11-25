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
            restoreNotice = RestoreNotice(context, { error("not needed") }, { error("not needed") }),
            registry = Lazy<DataSourceRegistry> { error("not needed") },
            ardBooks = { error("not needed") },
            scope = TestScope(),
        )
        val renamer = object : BookKeyRenamer {
            override suspend fun renameBookKeys(renames: Map<String, String>) {
                renamed += renames
            }
        }
        follower = BookKeyFollower(db, db.statsDao(), { backup }, { setOf(renamer) })
    }

    @After
    fun tearDown() {
        db.close()
        user.close()
    }

    @Test
    fun `a book under a new key keeps queue, statistics and drops the saved progress of the old key`() = runTest {
        listOf("group:old" to 60_000L, "group:new" to 5_000L, "group:other" to 1_000L).forEach { (key, ms) ->
            db.statsDao().addListening(
                ListeningStatEntity(source = ListeningStatEntity.SOURCE_APP, kind = BookKind.BOOK, groupKey = ARD_BOOK_SERVER_ID,
                    groupTitle = "ARD Sounds", itemKey = key, itemTitle = key, listenedMs = ms, lastListenedAt = ms),
                day = 20_000,
            )
        }
        user.openHelper.writableDatabase.execSQL(
            "INSERT INTO book_progress (serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty) " +
                "VALUES ('$ARD_BOOK_SERVER_ID', 'group:old', 60000, 600000, 0, 1.0, 100, 0)",
        )

        follower.moved(ARD_BOOK_SERVER_ID, mapOf("group:old" to "group:new"))

        // Both entries now count together under the new key; the days stay.
        assertEquals(
            mapOf("group:new" to 65_000L, "group:other" to 1_000L),
            db.statsDao().all().associate { it.itemKey to it.listenedMs },
        )
        assertEquals(66_000L, db.statsDao().allDays().sumOf { it.listenedMs })
        assertEquals(
            listOf(mapOf(BookKeys.encode(ARD_BOOK_SERVER_ID, "group:old") to BookKeys.encode(ARD_BOOK_SERVER_ID, "group:new"))),
            renamed,
        )
        assertEquals(0, user.openHelper.readableDatabase.query("SELECT * FROM book_progress").use { it.count })
    }
}
