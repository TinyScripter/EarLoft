package keinabersmart.kleinaberlaut.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.UserDatabase
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.WearSelectionEntity
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var main: KleinaberlautDatabase
    private lateinit var user: UserDatabase

    @Before
    fun setUp() {
        main = newDatabase()
        user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        main.close()
        user.close()
    }

    private fun newDatabase() =
        Room.inMemoryDatabaseBuilder(context, KleinaberlautDatabase::class.java).allowMainThreadQueries().build()

    private fun backupFor(db: KleinaberlautDatabase) = UserDataBackup(
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
        scope = TestScope(),
    )

    private suspend fun sources(db: KleinaberlautDatabase) {
        db.sourceDao().upsert(SourceEntity(sourceId = "local", type = SourceType.LOCAL, name = "Gerät"))
        db.sourceDao().upsert(SourceEntity(sourceId = "nav", type = SourceType.SUBSONIC, name = "Navidrome"))
    }

    private suspend fun album(db: KleinaberlautDatabase, artist: String, title: String) = db.mergeDao().insertAlbum(
        AlbumEntity(title = title, sortTitle = title, normTitle = title, albumArtistDisplay = artist, normAlbumArtist = artist),
    )

    /** Ein Song mit einer Quelle; [sourceId] `local` = Datei, `nav` = Server. */
    private suspend fun song(db: KleinaberlautDatabase, albumId: Long, track: Int, title: String, sourceId: String = "local", favorite: Boolean = false): Long {
        val songId = db.mergeDao().insertSong(
            SongEntity(
                albumId = albumId, title = title, normTitle = title, artistDisplay = "", trackNumber = track, durationMs = 1_000,
                dateAdded = 0, isFavorite = favorite, favoriteChangedAt = if (favorite) 500 else 0,
            ),
        )
        db.mergeDao().insertSongSource(SongSourceEntity(uid = "$sourceId:$title", songId = songId, sourceId = sourceId, remoteId = title, seenInGeneration = 0))
        return songId
    }

    private suspend fun fillOldDevice() {
        sources(main)
        val album = album(main, "element of crime", "damals hinterm mond")
        val a = song(main, album, 1, "damals hinterm mond", favorite = true)
        val b = song(main, album, 2, "kaffee und karin")
        val c = song(main, album, 3, "nimm mich mit", sourceId = "nav", favorite = true)
        val playlistId = main.playlistDao().insertPlaylist(PlaylistEntity(name = "Abends", updatedAt = 100))
        main.playlistDao().insertEntries(listOf(b, a, b).mapIndexed { i, id -> PlaylistEntryEntity(playlistId = playlistId, songId = id, position = i) })
        main.playlistDao().insertPlaylist(PlaylistEntity(name = "Vom Server", boundSourceId = "nav", remotePlaylistId = "p1", updatedAt = 100))
        main.backupDao().insertPlayEvents(listOf(PlayEventEntity(songId = a, playedAt = 1_000, listenedMs = 200_000), PlayEventEntity(songId = c, playedAt = 2_000, listenedMs = 1)))
        main.wearDao().upsert(WearSelectionEntity(WearSelectionKind.ALBUM.key(album), WearSelectionKind.ALBUM, album, addedAt = 7))
        main.wearDao().upsert(WearSelectionEntity(WearSelectionKind.PLAYLIST.key(playlistId), WearSelectionKind.PLAYLIST, playlistId, addedAt = 8))
    }

    @Test
    fun `mirror saves local playlists, favorites without server, play history and watch selections by key`() = runTest {
        fillOldDevice()
        backupFor(main).mirror()

        val dao = user.userDataDao()
        val playlist = dao.playlists().single()
        assertEquals("Abends", playlist.name)
        assertEquals(
            listOf("kaffee und karin", "damals hinterm mond", "kaffee und karin"),
            dao.playlistEntries().sortedBy { it.position }.map { it.song.title },
        )
        // Der Favorit mit Server-Quelle kommt mit dem Abgleich zurück.
        assertEquals(listOf("damals hinterm mond"), dao.favorites().map { it.song.title })
        assertEquals(2, dao.playEvents().size)
        assertEquals(setOf(WearSelectionKind.ALBUM, WearSelectionKind.PLAYLIST), dao.wearSelections().map { it.kind }.toSet())
    }

    @Test
    fun `a new device gets everything back once the songs are there, with new ids`() = runTest {
        fillOldDevice()
        backupFor(main).mirror()
        val saved = UserDataSnapshot.ofUser(
            user.userDataDao().servers(), user.userDataDao().spokenServers(), user.userDataDao().podcasts(),
            user.userDataDao().listeningStats(), user.userDataDao().listeningDays(), user.userDataDao().progress(),
            user.userDataDao().playlists(), user.userDataDao().playlistEntries(), user.userDataDao().favorites(),
            user.userDataDao().playEvents(), user.userDataDao().wearSelections(),
        )

        val newDevice = newDatabase()
        try {
            val backup = backupFor(newDevice)
            backup.restore(saved)
            // Vor dem Scan: Die Playlist steht leer da, alles andere wartet; der Spiegel verliert nichts.
            val waiting = backup.applySaved(saved, Pending.of(saved))
            assertEquals(1, waiting.playlists.size)
            assertEquals(1, waiting.favorites.size)
            assertEquals(2, waiting.playEvents.size)
            assertEquals(1, waiting.wearSelections.size)
            backup.mirror()
            assertEquals(3, user.userDataDao().playlistEntries().size)
            assertEquals(1, user.userDataDao().favorites().size)
            assertEquals(2, user.userDataDao().playEvents().size)

            // Der Scan legt die Songs in anderer Reihenfolge an, also mit anderen Ids.
            sources(newDevice)
            album(newDevice, "andere", "platte")
            val album = album(newDevice, "element of crime", "damals hinterm mond")
            val b = song(newDevice, album, 2, "kaffee und karin")
            val a = song(newDevice, album, 1, "damals hinterm mond")
            val rest = backup.applySaved(saved, waiting)

            val playlist = newDevice.backupDao().localPlaylists().single()
            assertEquals("Abends", playlist.name)
            assertEquals(listOf(b, a, b), newDevice.playlistDao().getSongIds(playlist.playlistId))
            assertTrue(newDevice.backupDao().favoriteStates(listOf(a)).single().isFavorite)
            assertEquals(listOf(1_000L), newDevice.backupDao().playedAt(a))
            val selections = newDevice.wearDao().getSelections().map { it.kind to it.refId }.toSet()
            assertEquals(setOf(WearSelectionKind.ALBUM to album, WearSelectionKind.PLAYLIST to playlist.playlistId), selections)
            // Nur der Server-Song fehlt noch.
            assertTrue(rest.playlists.isEmpty())
            assertEquals(listOf("nimm mich mit"), rest.playEvents.map { it.song.title })

            // Noch einmal anwenden ändert nichts (keine doppelten Einträge).
            backup.applySaved(saved, Pending.of(saved))
            assertEquals(listOf(1_000L), newDevice.backupDao().playedAt(a))
            assertEquals(3, newDevice.playlistDao().getSongIds(playlist.playlistId).size)
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `a playlist keeps missing songs in the backup until it is changed here`() = runTest {
        fillOldDevice()
        val backup = backupFor(main)
        backup.mirror()
        // Der Song verschwindet aus der Bibliothek (Datei gelöscht): Die Sicherung behält ihn.
        val kaffee = main.backupDao().songsOfAlbum("element of crime", "damals hinterm mond").first { it.track == 2 }.songId
        main.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE songId = $kaffee")
        backup.mirror()
        assertEquals(3, user.userDataDao().playlistEntries().size)

        // Erst eine Änderung der Playlist hier ersetzt die gesicherte Fassung.
        val playlistId = main.backupDao().localPlaylists().single().playlistId
        main.playlistDao().touch(playlistId, 200)
        backup.mirror()
        assertEquals(listOf("damals hinterm mond"), user.userDataDao().playlistEntries().map { it.song.title })
        assertFalse(user.userDataDao().favorites().isEmpty())
    }
}
