package de.kleinabersmart.kleinaberlaut.core.download

import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.kleinaberlaut.core.data.repository.CoverFiles
import de.kleinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.PlaylistEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.kleinaberlaut.core.model.DownloadState
import de.kleinabersmart.kleinaberlaut.core.model.OfflineMark
import de.kleinabersmart.kleinaberlaut.core.model.SongOfflineState
import de.kleinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var library: TestLibrary
    private lateinit var storage: DownloadStorage
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repository: DownloadRepository

    @Before
    fun setUp() = runTest {
        library = TestLibrary()
        storage = DownloadStorage(tempFolder.newFolder("downloads"))
        scheduler = RecordingScheduler()
        repository = DownloadRepository(
            database = library.db,
            downloadDao = library.db.downloadDao(),
            libraryDao = library.db.libraryDao(),
            playlistDao = library.db.playlistDao(),
            storage = storage,
            scheduler = scheduler,
            coverFiles = CoverFiles(ApplicationProvider.getApplicationContext()),
        )
        library.source("local", SourceType.LOCAL)
        library.source("home", priority = 0)
        library.source("office", priority = 5)
    }

    @After
    fun tearDown() {
        library.close()
    }

    @Test
    fun `album download skips local songs and prefers the higher priority server`() = runTest {
        val albumId = library.album("Takk")
        val local = library.song(albumId, "Glósóli", "local" to "1", "home" to "a")
        val remote = library.song(albumId, "Hoppípolla", "home" to "b", "office" to "x")

        assertEquals(1, repository.downloadAlbum(albumId))

        val downloads = library.db.downloadDao().getAll()
        assertEquals(listOf("office:x"), downloads.map { it.sourceUid })
        assertEquals("album:$albumId", downloads.single().pinnedBy)
        assertEquals(1, scheduler.calls)

        val status = repository.albumStatus(albumId).first()
        assertEquals(SongOfflineState.LOCAL, status.songs.getValue(local).state)
        assertEquals(SongOfflineState.QUEUED, status.songs.getValue(remote).state)
        assertEquals(1, status.downloadableCount)
    }

    @Test
    fun `downloading again does not duplicate but retries failed songs`() = runTest {
        val albumId = library.album("Takk")
        val songId = library.song(albumId, "Sæglópur", "home" to "c")
        library.db.downloadDao().insertIfAbsent(listOf(DownloadEntity("home:c", songId, DownloadState.FAILED)))

        assertEquals(1, repository.downloadAlbum(albumId))
        assertEquals(DownloadState.QUEUED, library.db.downloadDao().get("home:c")!!.state)

        assertEquals(0, repository.downloadAlbum(albumId))
        assertEquals(1, library.db.downloadDao().getAll().size)
    }

    @Test
    fun `fully local album needs no download`() = runTest {
        val albumId = library.album("Ágætis byrjun")
        library.song(albumId, "Svefn-g-englar", "local" to "7")

        assertEquals(0, repository.downloadAlbum(albumId))
        assertEquals(0, scheduler.calls)
        assertTrue(repository.albumStatus(albumId).first().isFullyOffline)
    }

    @Test
    fun `removing an album deletes entries and files`() = runTest {
        val albumId = library.album("Takk")
        val songId = library.song(albumId, "Milanó", "home" to "d")
        val file = storage.fileFor("home:d", "audio/flac").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        library.db.downloadDao().insertIfAbsent(
            listOf(DownloadEntity("home:d", songId, DownloadState.DONE, filePath = file.absolutePath, bytesTotal = 10)),
        )
        assertTrue(repository.albumStatus(albumId).first().isFullyOffline)

        repository.removeAlbum(albumId)

        assertFalse(file.exists())
        assertTrue(library.db.downloadDao().getAll().isEmpty())
        assertEquals(SongOfflineState.NONE, repository.albumStatus(albumId).first().songs.getValue(songId).state)
    }

    @Test
    fun `playlist download queues each song once and removing it deletes them`() = runTest {
        val albumId = library.album("Takk")
        val a = library.song(albumId, "A", "home" to "a")
        val b = library.song(albumId, "B", "home" to "b")
        val local = library.song(albumId, "C", "local" to "c")
        val playlistId = library.db.playlistDao().insertPlaylist(PlaylistEntity(name = "Mix", updatedAt = 0))
        library.db.playlistDao().insertEntries(
            listOf(a, b, a, local).mapIndexed { i, id -> PlaylistEntryEntity(playlistId = playlistId, songId = id, position = i) },
        )

        assertEquals(2, repository.downloadPlaylist(playlistId))
        assertEquals(setOf("home:a", "home:b"), library.db.downloadDao().getAll().map { it.sourceUid }.toSet())
        assertEquals("playlist:$playlistId", library.db.downloadDao().get("home:a")!!.pinnedBy)

        repository.removePlaylist(playlistId)
        assertTrue(library.db.downloadDao().getAll().isEmpty())
    }

    @Test
    fun `song statuses combine local songs and downloads`() = runTest {
        val albumId = library.album("Takk")
        val local = library.song(albumId, "A", "local" to "a", "home" to "a")
        val queued = library.song(albumId, "B", "home" to "b")
        val streamOnly = library.song(albumId, "C", "home" to "c")
        repository.downloadSongs(listOf(queued))

        val lookup = repository.songStatuses().first()

        assertEquals(SongOfflineState.LOCAL, lookup[local].state)
        assertEquals(SongOfflineState.QUEUED, lookup[queued].state)
        assertEquals(SongOfflineState.NONE, lookup[streamOnly].state)
        assertEquals(1, lookup.statusOf(listOf(local, queued, streamOnly, queued)).pendingCount)
    }

    @Test
    fun `album marks count local songs as present but need a download`() = runTest {
        val full = library.album("Takk")
        library.song(full, "A", "local" to "a")
        val fullRemote = library.song(full, "B", "home" to "b")
        val partial = library.album("Kveikur")
        val partialDone = library.song(partial, "C", "home" to "c")
        library.song(partial, "D", "home" to "d")
        val pending = library.album("Valtari")
        library.song(pending, "E", "home" to "e")
        val failed = library.album("Med sud")
        val failedSong = library.song(failed, "F", "home" to "f")
        val onlyLocal = library.album("( )")
        library.song(onlyLocal, "G", "local" to "g")
        val untouched = library.album("Heima")
        library.song(untouched, "H", "home" to "h")
        library.db.downloadDao().insertIfAbsent(
            listOf(
                DownloadEntity("home:b", fullRemote, DownloadState.DONE),
                DownloadEntity("home:c", partialDone, DownloadState.DONE),
                DownloadEntity("home:f", failedSong, DownloadState.FAILED),
            ),
        )
        repository.downloadAlbum(pending)

        val marks = repository.albumMarks().first()

        assertEquals(
            mapOf(full to OfflineMark.DONE, partial to OfflineMark.PARTIAL, pending to OfflineMark.PENDING, failed to OfflineMark.FAILED),
            marks,
        )
    }

    @Test
    fun `favorites are downloaded automatically and dropped when unstarred`() = runTest {
        val albumId = library.album("Takk")
        val liked = library.song(albumId, "A", "home" to "a")
        library.song(albumId, "B", "home" to "b")
        library.db.syncDao().setSongFavorite(liked, true, 1)

        repository.reconcileFavoriteDownloads(enabled = true)
        assertEquals("favorites", library.db.downloadDao().get("home:a")!!.pinnedBy)
        assertEquals(1, library.db.downloadDao().getAll().size)
        assertEquals(1, scheduler.calls)

        // Unverändert: nichts Neues, kein erneuter Auftrag.
        repository.reconcileFavoriteDownloads(enabled = true)
        assertEquals(1, scheduler.calls)

        library.db.syncDao().setSongFavorite(liked, false, 2)
        repository.reconcileFavoriteDownloads(enabled = true)
        assertTrue(library.db.downloadDao().getAll().isEmpty())
    }

    @Test
    fun `a manual download keeps a song that was fetched as favorite`() = runTest {
        val albumId = library.album("Takk")
        val liked = library.song(albumId, "A", "home" to "a")
        library.db.syncDao().setSongFavorite(liked, true, 1)
        repository.reconcileFavoriteDownloads(enabled = true)

        repository.downloadAlbum(albumId)
        library.db.syncDao().setSongFavorite(liked, false, 2)
        repository.reconcileFavoriteDownloads(enabled = true)

        assertEquals("album:$albumId", library.db.downloadDao().get("home:a")!!.pinnedBy)
    }

    @Test
    fun `switching the automatic downloads off keeps the files`() = runTest {
        val albumId = library.album("Takk")
        val liked = library.song(albumId, "A", "home" to "a")
        library.db.syncDao().setSongFavorite(liked, true, 1)
        repository.reconcileFavoriteDownloads(enabled = true)

        repository.reconcileFavoriteDownloads(enabled = false)
        library.db.syncDao().setSongFavorite(liked, false, 2)
        repository.reconcileFavoriteDownloads(enabled = false)

        assertEquals("manual", library.db.downloadDao().get("home:a")!!.pinnedBy)
    }

    @Test
    fun `failed favorite downloads are not retried on every reconcile`() = runTest {
        val albumId = library.album("Takk")
        val liked = library.song(albumId, "A", "home" to "a")
        library.db.syncDao().setSongFavorite(liked, true, 1)
        library.db.downloadDao().insertIfAbsent(listOf(DownloadEntity("home:a", liked, DownloadState.FAILED, pinnedBy = "favorites")))

        repository.reconcileFavoriteDownloads(enabled = true)

        assertEquals(DownloadState.FAILED, library.db.downloadDao().get("home:a")!!.state)
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `a fully downloaded server queues all its songs except local ones`() = runTest {
        val albumId = library.album("Takk")
        library.song(albumId, "A", "home" to "a")
        library.song(albumId, "B", "local" to "b", "home" to "b")
        library.song(albumId, "C", "office" to "c")

        repository.reconcileServerDownloads(setOf("home"))

        assertEquals(listOf("home:a"), library.db.downloadDao().getAll().map { it.sourceUid })
        assertEquals("server:home", library.db.downloadDao().get("home:a")!!.pinnedBy)
        assertEquals(1, scheduler.calls)

        // Neuer Song nach einem Abgleich kommt dazu, Vorhandenes löst keinen weiteren Auftrag aus.
        library.song(albumId, "D", "home" to "d")
        repository.reconcileServerDownloads(setOf("home"))
        assertEquals(setOf("home:a", "home:d"), library.db.downloadDao().getAll().map { it.sourceUid }.toSet())
        assertEquals(2, scheduler.calls)
    }

    @Test
    fun `a fully downloaded server takes over favorites and keeps them when unstarred`() = runTest {
        val albumId = library.album("Takk")
        val liked = library.song(albumId, "A", "home" to "a")
        library.db.syncDao().setSongFavorite(liked, true, 1)
        repository.reconcileFavoriteDownloads(enabled = true)

        repository.reconcileServerDownloads(setOf("home"))
        library.db.syncDao().setSongFavorite(liked, false, 2)
        repository.reconcileFavoriteDownloads(enabled = true)

        assertEquals("server:home", library.db.downloadDao().get("home:a")!!.pinnedBy)
    }

    @Test
    fun `switching a server off keeps its files as manual downloads`() = runTest {
        val albumId = library.album("Takk")
        library.song(albumId, "A", "home" to "a")
        library.song(albumId, "B", "office" to "b")
        repository.reconcileServerDownloads(setOf("home", "office"))

        repository.reconcileServerDownloads(setOf("office"))

        assertEquals("manual", library.db.downloadDao().get("home:a")!!.pinnedBy)
        assertEquals("server:office", library.db.downloadDao().get("office:b")!!.pinnedBy)
    }

    @Test
    fun `files of removed song sources are cleaned up`() = runTest {
        val albumId = library.album("Takk")
        val songId = library.song(albumId, "Gong", "home" to "e")
        val file = storage.fileFor("home:e", "audio/flac").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        library.db.downloadDao().insertIfAbsent(
            listOf(DownloadEntity("home:e", songId, DownloadState.DONE, filePath = file.absolutePath)),
        )

        library.db.sourceDao().delete("home")
        DownloadFileJanitor(library.db.downloadDao(), storage, scheduler).onSongSourcesRemoved()

        assertTrue(library.db.downloadDao().getAll().isEmpty())
        assertFalse(file.exists())
    }
}
