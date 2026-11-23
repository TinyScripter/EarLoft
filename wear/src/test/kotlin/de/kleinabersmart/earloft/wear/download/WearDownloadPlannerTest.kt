package de.kleinabersmart.earloft.wear.download

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import kotlinx.coroutines.flow.first
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

    private lateinit var db: EarLoftDatabase
    private lateinit var state: WatchState
    private lateinit var scheduler: RecordingScheduler
    private lateinit var planner: WearDownloadPlanner
    private lateinit var episodeQueue: EpisodeQueue
    private var albumId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        state = WatchState(ApplicationProvider.getApplicationContext())
        // The emulator's storage should not determine the result.
        state.updateSettings(WearSettingsDto(minFreeBytes = 0))
        scheduler = RecordingScheduler()
        episodeQueue = EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao())
        planner = WearDownloadPlanner(
            database = db,
            wearDao = db.wearDao(),
            playlistDao = db.playlistDao(),
            downloadDao = db.downloadDao(),
            storage = DownloadStorage(ApplicationProvider.getApplicationContext()),
            state = state,
            scheduler = scheduler,
            coverFiles = CoverFiles(ApplicationProvider.getApplicationContext()),
            podcastDao = db.podcastDao(),
            bookDao = db.bookDao(),
            episodeQueue = episodeQueue,
        )
        db.sourceDao().upsert(SourceEntity("nd", SourceType.SUBSONIC, "Navidrome"))
        db.sourceDao().upsert(SourceEntity("phone", SourceType.PHONE, "Phone"))
        albumId = db.mergeDao().insertAlbum(
            AlbumEntity(title = "Moonlight", sortTitle = "Moonlight", normTitle = "moonlight", albumArtistDisplay = "Band", normAlbumArtist = "band"),
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
    fun `episodes from the phone come first, finished ones are not loaded`() = runTest {
        // The planner estimates one hour of episode at 57.6 MB; no track fits next to it any more (3.296 MB each).
        state.updateSettings(WearSettingsDto(minFreeBytes = 0, storageLimitBytes = 60_000_000))
        db.podcastDao().upsertFeed(PodcastFeedEntity("f1", "https://example.org/feed", "Podcast"))
        val heard = episode("heard")
        val next = episode("next")
        db.bookDao().upsertProgress(BookProgressEntity(heard, 3_600_000, 3_600_000, finished = true, updatedAt = 1))
        episodeQueue.setOrder(listOf(heard, next))
        select("album:1", listOf(song("nd:t1")))

        planner.plan()

        assertEquals(TrackDownload.QUEUED, db.podcastDao().trackOf(next)!!.downloadState)
        assertEquals(TrackDownload.NONE, db.podcastDao().trackOf(heard)!!.downloadState)
        assertEquals(emptyList<String>(), db.downloadDao().getAll().map { it.sourceUid })
        assertEquals(1, state.missingForStorage.value)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `books come after episodes, whole or not at all, and never as an episode`() = runTest {
        // Episode 1 h = 57.6 MB; book with two files of 1 h each = 115.2 MB; the second book no longer fits.
        state.updateSettings(WearSettingsDto(minFreeBytes = 0, storageLimitBytes = 180_000_000))
        db.podcastDao().upsertFeed(PodcastFeedEntity("f1", "https://example.org/feed", "Podcast"))
        val first = book("b1", files = 2)
        val episode = episode("e1")
        val second = book("b2", files = 1)
        episodeQueue.setOrder(listOf(first, episode, second))

        planner.plan()

        assertEquals(TrackDownload.QUEUED, db.podcastDao().trackOf(episode)!!.downloadState)
        assertEquals(listOf(TrackDownload.QUEUED, TrackDownload.QUEUED), db.bookDao().tracks(first).map { it.downloadState })
        assertEquals(TrackDownload.NONE, db.bookDao().tracks(second).single().downloadState)
        // The episode planner does not touch books (their first file looks like an episode).
        assertEquals(1, db.podcastDao().countPendingDownloads())
        assertEquals(1, state.missingForStorage.value)
        assertEquals(1, scheduler.calls)
    }

    private suspend fun book(remoteId: String, files: Int): Long {
        val bookId = db.bookDao().insertBook(
            BookEntity(serverId = "abs", kind = BookKind.BOOK, remoteId = remoteId, title = remoteId, normTitle = remoteId, author = "Author", durationMs = files * 3_600_000L),
        )
        db.bookDao().insertTracks((0 until files).map { BookTrackEntity(bookId, it, "$remoteId-$it", it * 3_600_000L, 3_600_000) })
        return bookId
    }

    private suspend fun episode(guid: String): Long {
        val bookId = db.bookDao().insertBook(
            BookEntity(serverId = "f1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "Podcast", durationMs = 3_600_000),
        )
        db.podcastDao().insertTracks(listOf(BookTrackEntity(bookId, 0, "https://example.org/$guid.mp3", 0, 3_600_000)))
        return bookId
    }

    @Test
    fun `stops at the storage limit and reports the missing songs`() = runTest {
        // Opus: 200 s * 16,000 B/s + 3 % = 3.296 MB per track -> 2 fit under 7 MB.
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
        // Failed stays failed (retrying is up to the user).
        assertEquals(DownloadState.FAILED, byUid.getValue("nd:t1").state)
        assertEquals(DownloadState.QUEUED, byUid.getValue("nd:t2").state)
    }

    @Test
    fun `songs that only the phone has are queued from the phone source`() = runTest {
        val onServer = song("nd:t1")
        val onlyPhone = song("phone:local:9", sourceId = "phone")
        select("playlist:2", listOf(onServer, onlyPhone))

        planner.plan()

        assertEquals(listOf("nd:t1", "phone:local:9"), db.downloadDao().getAll().map { it.sourceUid }.sorted())
        assertEquals("phone:local:9", db.downloadDao().nextQueuedFromPhone()?.sourceUid)
        assertEquals("nd:t1", db.downloadDao().nextQueuedFromServers(emptyList())?.sourceUid)
        assertEquals(0, state.missingForStorage.value)
    }

    @Test
    fun `phone songs are estimated as original even with opus quality`() = runTest {
        // Opus would be 3.296 MB per track. The phone sends the original: 5 MB.
        state.updateSettings(WearSettingsDto(minFreeBytes = 0, storageLimitBytes = 7_000_000))
        select("album:1", listOf(song("phone:local:1", sourceId = "phone", sizeBytes = 5_000_000), song("phone:local:2", sourceId = "phone", sizeBytes = 5_000_000)))

        planner.plan()

        assertEquals(listOf("phone:local:1"), db.downloadDao().getAll().map { it.sourceUid })
        assertEquals(1, state.missingForStorage.value)
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
    fun `storage view counts loaded bytes per selection and per book`() = runTest {
        val t1 = song("nd:t1")
        val t2 = song("nd:t2")
        val t3 = song("nd:t3")
        // A duplicate in the playlist counts once; a song in two selections counts in both.
        select("album:1", listOf(t1, t2, t1, t3))
        select("favorites", listOf(t1), addedAt = 1)
        db.downloadDao().insertIfAbsent(
            listOf(
                DownloadEntity("nd:t1", t1, DownloadState.DONE, bytesDone = 1_000, bytesTotal = 1_000),
                DownloadEntity("nd:t2", t2, DownloadState.DONE, bytesDone = 2_000, bytesTotal = 2_000),
                DownloadEntity("nd:t3", t3, DownloadState.RUNNING, bytesDone = 100, bytesTotal = 4_000),
            ),
        )
        val bookId = db.bookDao().insertBook(
            BookEntity(serverId = "abs", kind = BookKind.BOOK, remoteId = "b1", title = "b1", normTitle = "b1", author = "Author", durationMs = 7_200_000),
        )
        val episodeId = db.bookDao().insertBook(
            BookEntity(serverId = "f1", kind = BookKind.EPISODE, remoteId = "e1", title = "e1", normTitle = "e1", author = "Podcast", durationMs = 3_600_000),
        )
        db.bookDao().insertTracks(
            listOf(
                BookTrackEntity(bookId, 0, "b1-0", 0, 3_600_000, downloadState = TrackDownload.DONE, fileSize = 500),
                BookTrackEntity(bookId, 1, "b1-1", 3_600_000, 3_600_000, downloadState = TrackDownload.RUNNING, fileSize = 50),
                BookTrackEntity(episodeId, 0, "https://example.org/e1.mp3", 0, 3_600_000, downloadState = TrackDownload.DONE, fileSize = 300),
            ),
        )

        val selections = db.wearDao().observeSelectionProgress().first().associateBy { it.key }
        assertEquals(3_000L, selections.getValue("album:1").bytes)
        assertEquals(2, selections.getValue("album:1").done)
        // t3 is running: 100 of 4,000 bytes.
        assertEquals(0.025, selections.getValue("album:1").runningFraction, 1e-9)
        assertEquals(1_000L, selections.getValue("favorites").bytes)

        val books = db.bookDao().observeStoredBooks().first().associateBy { it.remoteId }
        assertEquals("2/1/500", books.getValue("b1").let { "${it.total}/${it.done}/${it.doneBytes}" })
        assertEquals("1/1/300", books.getValue("e1").let { "${it.total}/${it.done}/${it.doneBytes}" })
        assertEquals(BookKind.EPISODE, books.getValue("e1").kind)
    }

    @Test
    fun `nothing to do does not start the worker`() = runTest {
        planner.plan()

        assertEquals(0, scheduler.calls)
    }

    private var songCounter = 0

    /** A 200 s song with one source [uid] (`<sourceId>:<remoteId>`); returns the songId. */
    private suspend fun song(uid: String, sourceId: String = "nd", sizeBytes: Long? = null): Long {
        songCounter++
        val songId = db.mergeDao().insertSong(
            SongEntity(
                albumId = albumId,
                title = "Track $songCounter",
                normTitle = "track $songCounter",
                artistDisplay = "Band",
                durationMs = 200_000,
                dateAdded = 0,
            ),
        )
        db.mergeDao().insertSongSource(
            SongSourceEntity(
                uid = uid,
                songId = songId,
                sourceId = sourceId,
                remoteId = uid.substringAfter(':'),
                mimeType = "audio/flac",
                sizeBytes = sizeBytes,
                seenInGeneration = 1,
            ),
        )
        return songId
    }

    /** A selection as on the watch: `refId` points to the device playlist. */
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
