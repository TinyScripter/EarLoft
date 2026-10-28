package keinabersmart.kleinaberlaut.wear.download

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.WearSelectionEntity
import keinabersmart.kleinaberlaut.core.download.DownloadScheduler
import keinabersmart.kleinaberlaut.core.download.DownloadStorage
import keinabersmart.kleinaberlaut.core.model.DownloadState
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WearDownloadPlannerTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var state: WatchState
    private lateinit var scheduler: RecordingScheduler
    private lateinit var planner: WearDownloadPlanner
    private var albumId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        state = WatchState(ApplicationProvider.getApplicationContext())
        // Der Emulator-Speicher soll das Ergebnis nicht bestimmen.
        state.updateSettings(WearSettingsDto(minFreeBytes = 0))
        scheduler = RecordingScheduler()
        planner = WearDownloadPlanner(
            database = db,
            wearDao = db.wearDao(),
            playlistDao = db.playlistDao(),
            downloadDao = db.downloadDao(),
            storage = DownloadStorage(ApplicationProvider.getApplicationContext()),
            state = state,
            scheduler = scheduler,
        )
        db.sourceDao().upsert(SourceEntity("nd", SourceType.SUBSONIC, "Navidrome"))
        db.sourceDao().upsert(SourceEntity("phone", SourceType.PHONE, "Handy"))
        albumId = db.libraryDao().insertAlbum(
            AlbumEntity(title = "Mondlicht", sortTitle = "Mondlicht", normTitle = "mondlicht", albumArtistDisplay = "Band", normAlbumArtist = "band"),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `queues the missing songs of the selections and starts the worker`() = runTest {
        val songs = listOf(song("nd:t1"), song("nd:t2"), song("nd:t3"))
        select("album:1", songs)

        planner.plan()

        val downloads = db.downloadDao().getAll().sortedBy { it.sourceUid }
        assertEquals(listOf("nd:t1", "nd:t2", "nd:t3"), downloads.map { it.sourceUid })
        assertEquals(setOf(DownloadState.QUEUED), downloads.map { it.state }.toSet())
        assertEquals(setOf("wear:album:1"), downloads.map { it.pinnedBy }.toSet())
        assertEquals(0, state.missingForStorage.value)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `stops at the storage limit and reports the missing songs`() = runTest {
        // Opus: 200 s * 16 000 B/s + 3 % = 3,296 MB je Titel -> 2 passen unter 7 MB.
        state.updateSettings(WearSettingsDto(minFreeBytes = 0, storageLimitBytes = 7_000_000))
        select("album:1", listOf(song("nd:t1"), song("nd:t2"), song("nd:t3")))

        planner.plan()

        assertEquals(listOf("nd:t1", "nd:t2"), db.downloadDao().getAll().map { it.sourceUid }.sorted())
        assertEquals(1, state.missingForStorage.value)
    }

    @Test
    fun `existing downloads are not queued again and count towards the budget`() = runTest {
        val songs = listOf(song("nd:t1"), song("nd:t2"))
        select("album:1", songs)
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:t1", songs[0], DownloadState.FAILED)))

        planner.plan()

        val byUid = db.downloadDao().getAll().associateBy { it.sourceUid }
        // Fehlgeschlagenes bleibt fehlgeschlagen (Erneut versuchen ist Sache des Nutzers).
        assertEquals(DownloadState.FAILED, byUid.getValue("nd:t1").state)
        assertEquals(DownloadState.QUEUED, byUid.getValue("nd:t2").state)
    }

    @Test
    fun `songs that only the phone has are left for the phone fallback`() = runTest {
        val onServer = song("nd:t1")
        val onlyPhone = song("phone:local:9", sourceId = "phone")
        select("playlist:2", listOf(onServer, onlyPhone))

        planner.plan()

        assertEquals(listOf("nd:t1"), db.downloadDao().getAll().map { it.sourceUid })
        assertEquals(0, state.missingForStorage.value)
    }

    @Test
    fun `a song in two selections is queued once for the first one`() = runTest {
        val shared = song("nd:t1")
        select("album:1", listOf(shared), addedAt = 0)
        select("favorites", listOf(shared), addedAt = 1)

        planner.plan()

        assertEquals(listOf("wear:album:1"), db.downloadDao().getAll().map { it.pinnedBy })
    }

    @Test
    fun `nothing to do does not start the worker`() = runTest {
        planner.plan()

        assertEquals(0, scheduler.calls)
    }

    private var songCounter = 0

    /** Ein 200 s langer Song mit einer Quelle [uid] (`<sourceId>:<remoteId>`); liefert die songId. */
    private suspend fun song(uid: String, sourceId: String = "nd"): Long {
        songCounter++
        val songId = db.libraryDao().insertSong(
            SongEntity(
                albumId = albumId,
                title = "Titel $songCounter",
                normTitle = "titel $songCounter",
                artistDisplay = "Band",
                durationMs = 200_000,
                dateAdded = 0,
            ),
        )
        db.libraryDao().insertSongSource(
            SongSourceEntity(
                uid = uid,
                songId = songId,
                sourceId = sourceId,
                remoteId = uid.substringAfter(':'),
                mimeType = "audio/flac",
                seenInGeneration = 1,
            ),
        )
        return songId
    }

    /** Eine Auswahl wie auf der Uhr: `refId` zeigt auf die Geräte-Playlist. */
    private suspend fun select(key: String, songIds: List<Long>, addedAt: Long = 0) {
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = key, updatedAt = 0))
        db.playlistDao().insertEntries(
            songIds.mapIndexed { position, songId -> PlaylistEntryEntity(playlistId = playlistId, songId = songId, position = position) },
        )
        val kind = when {
            key.startsWith("album") -> WearSelectionKind.ALBUM
            key.startsWith("playlist") -> WearSelectionKind.PLAYLIST
            else -> WearSelectionKind.FAVORITES
        }
        db.wearDao().upsert(WearSelectionEntity(key, kind, playlistId, addedAt))
    }

    private class RecordingScheduler : DownloadScheduler {
        var calls = 0

        override suspend fun enqueue(replace: Boolean) {
            calls++
        }
    }
}
