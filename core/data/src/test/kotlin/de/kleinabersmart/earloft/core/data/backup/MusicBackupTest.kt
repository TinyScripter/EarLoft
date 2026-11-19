package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.AlbumSourceEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind
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
    private lateinit var main: EarLoftDatabase
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
        Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()

    private fun backupFor(db: EarLoftDatabase) = UserDataBackup(
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

    private suspend fun sources(db: EarLoftDatabase) {
        db.sourceDao().upsert(SourceEntity(sourceId = "local", type = SourceType.LOCAL, name = "Device"))
        db.sourceDao().upsert(SourceEntity(sourceId = "nav", type = SourceType.SUBSONIC, name = "Navidrome"))
    }

    private suspend fun album(db: EarLoftDatabase, artist: String, title: String) = db.mergeDao().insertAlbum(
        AlbumEntity(title = title, sortTitle = title, normTitle = title, albumArtistDisplay = artist, normAlbumArtist = artist),
    )

    /** A song with one source; [sourceId] `local` = file, `nav` = server. */
    private suspend fun song(db: EarLoftDatabase, albumId: Long, track: Int, title: String, sourceId: String = "local", favorite: Boolean = false): Long {
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
        val playlistId = main.playlistDao().insertPlaylist(PlaylistEntity(name = "Evenings", updatedAt = 100))
        main.playlistDao().insertEntries(listOf(b, a, b).mapIndexed { i, id -> PlaylistEntryEntity(playlistId = playlistId, songId = id, position = i) })
        main.playlistDao().insertPlaylist(PlaylistEntity(name = "From server", boundSourceId = "nav", remotePlaylistId = "p1", updatedAt = 100))
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
        assertEquals("Evenings", playlist.name)
        assertEquals(
            listOf("kaffee und karin", "damals hinterm mond", "kaffee und karin"),
            dao.playlistEntries().sortedBy { it.position }.map { it.song.title },
        )
        // The favorite with a server source comes back with the sync.
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
            // Before the scan: the playlist stands empty, everything else waits; the mirror loses nothing.
            val waiting = backup.applySaved(saved, Pending.of(saved))
            assertEquals(1, waiting.playlists.size)
            assertEquals(1, waiting.favorites.size)
            assertEquals(2, waiting.playEvents.size)
            assertEquals(1, waiting.wearSelections.size)
            backup.mirror()
            assertEquals(3, user.userDataDao().playlistEntries().size)
            assertEquals(1, user.userDataDao().favorites().size)
            assertEquals(2, user.userDataDao().playEvents().size)

            // The scan creates the songs in a different order, i.e. with different ids.
            sources(newDevice)
            album(newDevice, "other", "record")
            val album = album(newDevice, "element of crime", "damals hinterm mond")
            val b = song(newDevice, album, 2, "kaffee und karin")
            val a = song(newDevice, album, 1, "damals hinterm mond")
            val rest = backup.applySaved(saved, waiting)

            val playlist = newDevice.backupDao().localPlaylists().single()
            assertEquals("Evenings", playlist.name)
            assertEquals(listOf(b, a, b), newDevice.playlistDao().getSongIds(playlist.playlistId))
            assertTrue(newDevice.backupDao().favoriteStates(listOf(a)).single().isFavorite)
            assertEquals(listOf(1_000L), newDevice.backupDao().playedAt(a))
            val selections = newDevice.wearDao().getSelections().map { it.kind to it.refId }.toSet()
            assertEquals(setOf(WearSelectionKind.ALBUM to album, WearSelectionKind.PLAYLIST to playlist.playlistId), selections)
            // Only the server song is still missing.
            assertTrue(rest.playlists.isEmpty())
            assertEquals(listOf("nimm mich mit"), rest.playEvents.map { it.song.title })

            // Applying again changes nothing (no duplicate entries).
            backup.applySaved(saved, Pending.of(saved))
            assertEquals(listOf(1_000L), newDevice.backupDao().playedAt(a))
            assertEquals(3, newDevice.playlistDao().getSongIds(playlist.playlistId).size)
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `album and artist favorites without server are saved and come back on a new device`() = runTest {
        sources(main)
        val local = album(main, "element of crime", "damals hinterm mond")
        song(main, local, 1, "damals hinterm mond")
        val fromServer = album(main, "element of crime", "lieblingsfarben und tiere")
        song(main, fromServer, 1, "lieblingsfarben und tiere", sourceId = "nav")
        main.mergeDao().insertAlbumSource(AlbumSourceEntity("nav", "al1", fromServer, seenInGeneration = 0))
        val artist = main.mergeDao().insertArtist(ArtistEntity(name = "Element of Crime", sortName = "", normName = "element of crime"))
        main.syncDao().setAlbumFavorite(local, true, 300)
        main.syncDao().setAlbumFavorite(fromServer, true, 301)
        main.syncDao().setArtistFavorite(artist, true, 302)
        backupFor(main).mirror()

        // The album from the server is held by the server.
        val saved = user.userDataDao().snapshot()
        assertEquals(
            setOf(CollectionKey(FavoriteKind.ALBUM, "element of crime", "damals hinterm mond"), CollectionKey(FavoriteKind.ARTIST, "element of crime", "")),
            saved.collectionFavorites.keys,
        )

        val newDevice = newDatabase()
        try {
            val backup = backupFor(newDevice)
            backup.restore(saved)
            val waiting = backup.applySaved(saved, Pending.of(saved))
            assertEquals(2, waiting.collectionFavorites.size)
            // The mirror doesn't delete what is still waiting for its album or artist.
            backup.mirror()
            assertEquals(2, user.userDataDao().collectionFavorites().size)

            sources(newDevice)
            val album = album(newDevice, "element of crime", "damals hinterm mond")
            song(newDevice, album, 1, "damals hinterm mond")
            val newArtist = newDevice.mergeDao().insertArtist(ArtistEntity(name = "Element of Crime", sortName = "", normName = "element of crime"))
            val rest = backup.applySaved(saved, waiting)

            assertTrue(rest.collectionFavorites.isEmpty())
            assertEquals(true, newDevice.syncDao().isAlbumFavorite(album))
            assertEquals(true, newDevice.syncDao().isArtistFavorite(newArtist))
        } finally {
            newDevice.close()
        }
    }

    @Test
    fun `a playlist keeps missing songs in the backup until it is changed here`() = runTest {
        fillOldDevice()
        val backup = backupFor(main)
        backup.mirror()
        // The song disappears from the library (file deleted): the backup keeps it.
        val kaffee = main.backupDao().songsOfAlbum("element of crime", "damals hinterm mond").first { it.track == 2 }.songId
        main.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE songId = $kaffee")
        backup.mirror()
        assertEquals(3, user.userDataDao().playlistEntries().size)

        // Only a change of the playlist here replaces the backed-up version.
        val playlistId = main.backupDao().localPlaylists().single().playlistId
        main.playlistDao().touch(playlistId, 200)
        backup.mirror()
        assertEquals(listOf("damals hinterm mond"), user.userDataDao().playlistEntries().map { it.song.title })
        assertFalse(user.userDataDao().favorites().isEmpty())
    }
}
