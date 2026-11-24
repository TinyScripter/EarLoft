package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlayReporter
import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.earloft.core.data.spoken.BookKeys
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The saved queue on a real database and DataStore: what comes back after a restart. */
@RunWith(RobolectricTestRunner::class)
class QueuePersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val marker = File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER)
    private lateinit var db: EarLoftDatabase
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var persistence: QueuePersistence
    private var albumId = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        dataStore = PreferenceDataStoreFactory.create { File(folder.root, "playback_state.preferences_pb") }
        persistence = persistence(db)
        db.sourceDao().upsert(SourceEntity(sourceId = SOURCE, type = SourceType.LOCAL, name = "Device"))
        albumId = db.mergeDao().insertAlbum(
            AlbumEntity(title = "Album", sortTitle = "Album", normTitle = "album", albumArtistDisplay = "Artist", normAlbumArtist = "artist"),
        )
    }

    @After
    fun tearDown() {
        db.close()
        marker.delete()
    }

    private fun persistence(db: EarLoftDatabase) = QueuePersistence(
        dataStore,
        PlaybackStateRepository(db.playbackDao(), NoReporter),
        LibraryRepository(db.libraryDao()),
        BookKeys(context, db.bookDao()),
    )

    private object NoReporter : PlayReporter {
        override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean) = Unit
    }

    private suspend fun songs(vararg titles: String): List<Long> = titles.mapIndexed { i, title ->
        val songId = db.mergeDao().insertSong(
            SongEntity(albumId = albumId, title = title, normTitle = title.lowercase(), artistDisplay = "Artist", trackNumber = i + 1, durationMs = 1_000, dateAdded = 0),
        )
        db.mergeDao().insertSongSource(SongSourceEntity(uid = "$SOURCE:$songId", songId = songId, sourceId = SOURCE, remoteId = "$songId", seenInGeneration = 1))
        songId
    }

    private suspend fun episode(db: EarLoftDatabase, guid: String) = db.bookDao().upsertBook(
        BookEntity(serverId = "feed-1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 1_000),
    )

    /** Runs [block] with a scope and waits for everything it launched. */
    private suspend fun settled(block: (CoroutineScope) -> Unit) = coroutineScope { block(this) }

    @Test
    fun `restore starts at the saved title with its position and modes`() = runBlocking {
        val ids = songs("A", "B", "C")
        db.playbackDao().replaceQueue(ids)
        settled { persistence.saveMusicPosition(index = 1, position = 42_000, shuffle = true, repeat = Player.REPEAT_MODE_ALL, scope = it) }

        val restored = persistence.restore()!!

        assertEquals(ids.map { MediaIds.song(it) }, restored.items.map { it.mediaId })
        assertEquals(1, restored.startIndex)
        assertEquals(42_000L, restored.startPositionMs)
        assertTrue(restored.shuffle)
        assertEquals(Player.REPEAT_MODE_ALL, restored.repeatMode)
        assertTrue(persistence.savedShuffle())
    }

    /** Like a removed source: the song is still in the database, just not playable any more. */
    private fun hide(songId: Long) = db.openHelper.writableDatabase.execSQL("DELETE FROM song_sources WHERE songId = $songId")

    /** Like a sync that no longer finds the song: it is deleted, and with it its row in the saved queue. */
    private suspend fun delete(songId: Long) {
        hide(songId)
        db.libraryDao().cleanupOrphans()
    }

    @Test
    fun `a hidden saved title starts the one after it from its start`() = runBlocking {
        val (a, b, c) = songs("A", "B", "C")
        db.playbackDao().replaceQueue(listOf(a, b, c))
        settled { persistence.saveMusicPosition(index = 1, position = 42_000, shuffle = false, repeat = Player.REPEAT_MODE_OFF, scope = it) }
        hide(b)

        val restored = persistence.restore()!!

        assertEquals(listOf(a, c).map { MediaIds.song(it) }, restored.items.map { it.mediaId })
        assertEquals(1, restored.startIndex)
        assertEquals(0L, restored.startPositionMs)
    }

    @Test
    fun `a deleted saved title starts the one after it from its start`() = runBlocking {
        val (a, b, c) = songs("A", "B", "C")
        db.playbackDao().replaceQueue(listOf(a, b, c))
        settled { persistence.saveMusicPosition(index = 1, position = 42_000, shuffle = false, repeat = Player.REPEAT_MODE_OFF, scope = it) }
        delete(b)

        val restored = persistence.restore()!!

        assertEquals(listOf(a, c).map { MediaIds.song(it) }, restored.items.map { it.mediaId })
        assertEquals(1, restored.startIndex)
        assertEquals(0L, restored.startPositionMs)
    }

    @Test
    fun `a song deleted before the saved title keeps the saved title and its position`() = runBlocking {
        val (a, b, c) = songs("A", "B", "C")
        db.playbackDao().replaceQueue(listOf(a, b, c))
        settled { persistence.saveMusicPosition(index = 1, position = 42_000, shuffle = false, repeat = Player.REPEAT_MODE_OFF, scope = it) }
        delete(a)

        val restored = persistence.restore()!!

        assertEquals(listOf(b, c).map { MediaIds.song(it) }, restored.items.map { it.mediaId })
        assertEquals(0, restored.startIndex)
        assertEquals(42_000L, restored.startPositionMs)
        assertEquals(0, persistence.savedQueue.first()!!.index)

        // Adding during a book still lands right after the saved title.
        val (d) = songs("D")
        persistence.addToSavedQueue(listOf(d), next = true)
        val withD = persistence.restore()!!
        assertEquals(listOf(b, d, c).map { MediaIds.song(it) }, withD.items.map { it.mediaId })
        assertEquals(0, withD.startIndex)
        assertEquals(42_000L, withD.startPositionMs)
    }

    @Test
    fun `songs added after every saved song was deleted start at the first of them`() = runBlocking {
        val (a, b) = songs("A", "B")
        db.playbackDao().replaceQueue(listOf(a))
        settled { persistence.saveMusicPosition(index = 0, position = 5_000, shuffle = false, repeat = Player.REPEAT_MODE_OFF, scope = it) }
        delete(a)

        persistence.addToSavedQueue(listOf(b), next = true)

        val restored = persistence.restore()!!
        assertEquals(listOf(MediaIds.song(b)), restored.items.map { it.mediaId })
        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.startPositionMs)
    }

    @Test
    fun `an empty saved queue restores nothing`() = runBlocking {
        assertNull(persistence.restore())
        assertNull(persistence.savedQueue.first())
    }

    @Test
    fun `a title played to the end is saved at its start`() = runBlocking {
        val ids = songs("A", "B")
        val player = QueuePlayer(ids, current = 1, positionMs = 1_000, state = Player.STATE_ENDED)

        settled { persistence.savePosition(player, it) }
        db.playbackDao().replaceQueue(ids)

        val restored = persistence.restore()!!
        assertEquals(1, restored.startIndex)
        assertEquals(0L, restored.startPositionMs)
    }

    @Test
    fun `songs added during a book follow the queue the music has not saved yet`() = runBlocking {
        val (a, b, c, d) = songs("A", "B", "C", "D")
        db.playbackDao().replaceQueue(listOf(a))
        settled { persistence.saveMusicPosition(index = 0, position = 5_000, shuffle = false, repeat = Player.REPEAT_MODE_OFF, scope = it) }

        // The music's queue changed to A, B, C right before the switch; its delayed save is still pending.
        val pending = Job()
        persistence.saveQueueDebounced(QueuePlayer(listOf(a, b, c), current = 0), CoroutineScope(coroutineContext + pending))
        persistence.addToSavedQueue(listOf(d), next = true)

        assertEquals(listOf(a, d, b, c), db.playbackDao().getQueueSongIds())
        // The delayed save runs later and must not drop D again.
        pending.complete()
        pending.join()
        assertEquals(listOf(a, d, b, c), db.playbackDao().getQueueSongIds())
        assertEquals(5_000L, persistence.restore()!!.startPositionMs)
    }

    @Test
    fun `songs added to an empty saved queue start at the first of them`() = runBlocking {
        val (a, b) = songs("A", "B")
        settled { persistence.saveMusicPosition(index = 3, position = 5_000, shuffle = false, repeat = Player.REPEAT_MODE_OFF, scope = it) }

        persistence.addToSavedQueue(listOf(a, b), next = false)

        assertEquals(listOf(a, b), db.playbackDao().getQueueSongIds())
        val saved = persistence.savedQueue.first()!!
        assertEquals(0, saved.index)
        assertEquals(listOf("A", "B"), saved.entries.map { it.title })
        assertEquals(0L, persistence.restore()!!.startPositionMs)
    }

    @Test
    fun `casting takes over the current song near the saved title`() = runBlocking {
        val (a, b) = songs("A", "B")
        db.playbackDao().replaceQueue(listOf(a, b, a, b))
        settled { persistence.saveMusicPosition(index = 2, position = 0, shuffle = true, repeat = Player.REPEAT_MODE_OFF, scope = it) }

        settled { persistence.saveRemotePosition(b, 7_000, it) }
        assertEquals(3, persistence.restore()!!.startIndex)
        assertEquals(7_000L, persistence.restore()!!.startPositionMs)

        val (unknown) = songs("Enqueued while casting")
        settled { persistence.saveRemotePosition(unknown, 9_000, it) }
        assertEquals(3, persistence.restore()!!.startIndex)
        assertEquals(7_000L, persistence.restore()!!.startPositionMs)
        assertTrue(persistence.restore()!!.shuffle)
    }

    @Test
    fun `the last book is found again by its key on a restored device`() = runBlocking {
        val episode = episode(db, "episode-7")
        settled { persistence.saveLastBook(episode, it) }

        val newDevice = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        try {
            val restored = persistence(newDevice)
            assertNull(restored.lastBookId())
            episode(newDevice, "other")
            val id = episode(newDevice, "episode-7")
            assertEquals(id, restored.lastBookId())
            assertEquals(id, restored.lastBookIds.first())
        } finally {
            newDevice.close()
        }

        settled { persistence.saveLastBook(null, it) }
        assertNull(persistence.lastBookId())
    }

    @Test
    fun `an old book id is rewritten into its key once`() = runBlocking {
        val id = episode(db, "episode-1")
        dataStore.edit { it[longPreferencesKey("last_book_id")] = id }

        assertEquals(id, persistence.lastBookId())
        val prefs = dataStore.data.first()
        assertFalse(longPreferencesKey("last_book_id") in prefs)
        assertEquals(id, persistence.lastBookIds.first())

        // "No book" in the old form means music.
        dataStore.edit {
            it.clear()
            it[longPreferencesKey("last_book_id")] = -1L
        }
        assertNull(persistence.lastBookId())
        assertNull(persistence.lastBookIds.first())
    }

    @Test
    fun `a renamed book key keeps the last book`() = runBlocking {
        val old = episode(db, "episode-1")
        settled { persistence.saveLastBook(old, it) }
        val oldKey = BookKeys(context, db.bookDao()).keyOf(old)!!
        val renamed = episode(db, "episode-1-new")
        val newKey = BookKeys(context, db.bookDao()).keyOf(renamed)!!

        persistence.renameBookKeys(mapOf(oldKey to newKey, "unrelated" to "other"))

        assertEquals(renamed, persistence.lastBookId())
    }

    /** A fixed queue of `song:<id>` items. */
    private class QueuePlayer(
        songIds: List<Long>,
        private val current: Int,
        private val positionMs: Long = 0,
        private val state: Int = Player.STATE_READY,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        private val items = songIds.map { MediaItem.Builder().setMediaId(MediaIds.song(it)).build() }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else current)
            .setContentPositionMs(positionMs)
            .setPlaybackState(state)
            .build()
    }

    private companion object {
        const val SOURCE = "local"
    }
}
