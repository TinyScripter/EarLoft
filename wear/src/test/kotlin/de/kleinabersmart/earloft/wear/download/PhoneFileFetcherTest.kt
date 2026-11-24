package de.kleinabersmart.earloft.wear.download

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.DownloadOutcome
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.wearsync.FileDoesNotFit
import de.kleinabersmart.earloft.core.wearsync.FileReceive
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearCoverFile
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.wear.sync.WearManifestSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Files from the phone on the watch: what happens to the entry and the partial file on success, a missing file, a full
 * storage, an abort and a removed entry. Real database and files; only the channel to the phone is a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhoneFileFetcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase
    private val storage = DownloadStorage(context)
    private val episodeStorage = EpisodeStorage(context)
    private val bookStorage = BookStorage(context)
    private val channel = FakeChannel()
    private lateinit var fetcher: PhoneFileFetcher

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        fetcher = PhoneFileFetcher(db.downloadDao(), storage, channel, db.podcastDao(), db.bookDao(), episodeStorage, bookStorage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** What the phone answers for the next transfer: by default the whole file. */
    private class FakeChannel : PhoneChannel {
        var nodeId: String? = "phone-1"
        val requested = mutableListOf<String>()
        var answer: suspend (part: File, onProgress: suspend (Long, Long) -> Unit) -> FileReceive = { part, onProgress ->
            part.appendBytes(CONTENT)
            onProgress(CONTENT.size.toLong(), CONTENT.size.toLong())
            FileReceive.Received(CONTENT.size.toLong())
        }

        override suspend fun phoneNodeId(): String? = nodeId

        override suspend fun receiveResumable(
            nodeId: String,
            sourceUid: String,
            part: File,
            lengthFile: File,
            fits: (missingBytes: Long) -> Boolean,
            onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit,
        ): FileReceive {
            requested += sourceUid
            lengthFile.writeText(CONTENT.size.toString())
            return answer(part, onProgress)
        }

        override suspend fun receive(nodeId: String, sourceUid: String, output: OutputStream): FileReceive {
            requested += sourceUid
            if (sourceUid.endsWith("missing")) return FileReceive.Missing
            output.write(CONTENT)
            return FileReceive.Received(CONTENT.size.toLong())
        }
    }

    // --- Songs ---

    /** A song only the phone has, queued for the watch. */
    private suspend fun queuedSong(remoteId: String = "local:5"): DownloadEntity {
        db.sourceDao().upsert(SourceEntity(WearManifestSource.PHONE_SOURCE_ID, SourceType.PHONE, "Phone"))
        val albumId = db.mergeDao().insertAlbum(AlbumEntity(title = "Album", sortTitle = "Album", normTitle = "album", albumArtistDisplay = "A", normAlbumArtist = "a"))
        val songId = db.mergeDao().insertSong(SongEntity(albumId = albumId, title = "Song", normTitle = "song", artistDisplay = "A", trackNumber = 1, durationMs = 1_000, dateAdded = 0))
        val uid = "${WearManifestSource.PHONE_SOURCE_ID}:$remoteId"
        db.mergeDao().insertSongSource(SongSourceEntity(uid = uid, songId = songId, sourceId = WearManifestSource.PHONE_SOURCE_ID, remoteId = remoteId, seenInGeneration = 1, mimeType = "audio/mpeg"))
        val download = DownloadEntity(sourceUid = uid, songId = songId, state = DownloadState.QUEUED)
        db.downloadDao().insertIfAbsent(listOf(download))
        return download
    }

    @Test
    fun `a song comes under the phone's own name and is stored complete`() = runTest {
        val download = queuedSong(remoteId = "local:5")

        assertEquals(DownloadOutcome.Done, fetcher.fetch(download))

        assertEquals(listOf("local:5"), channel.requested)
        val stored = db.downloadDao().get(download.sourceUid)!!
        assertEquals(DownloadState.DONE, stored.state)
        assertArrayEquals(CONTENT, File(stored.filePath!!).readBytes())
        assertFalse(storage.phonePartFileFor(download.sourceUid).exists())
    }

    @Test
    fun `without phone the song waits and keeps running for the caller`() = runTest {
        val download = queuedSong()
        channel.nodeId = null

        assertTrue(fetcher.fetch(download) is DownloadOutcome.Retry)

        assertEquals(DownloadState.RUNNING, db.downloadDao().get(download.sourceUid)!!.state)
    }

    @Test
    fun `a song the phone no longer has fails`() = runTest {
        val download = queuedSong()
        channel.answer = { _, _ -> FileReceive.Missing }

        assertEquals(DownloadOutcome.Failed(PhoneFileFetcher.MISSING_ON_PHONE), fetcher.fetch(download))

        assertEquals(DownloadState.FAILED, db.downloadDao().get(download.sourceUid)!!.state)
    }

    @Test
    fun `an aborted transfer keeps what arrived for the next attempt`() = runTest {
        val download = queuedSong()
        channel.answer = { part, _ ->
            part.appendBytes(CONTENT.copyOf(3))
            throw IOException("Phone out of range")
        }

        assertTrue(fetcher.fetch(download) is DownloadOutcome.Retry)

        assertEquals(3L, storage.phonePartFileFor(download.sourceUid).length())
        assertTrue(storage.phoneLengthFileFor(download.sourceUid).exists())
    }

    @Test
    fun `a song that does not fit gives up its entry and names its size`() = runTest {
        val download = queuedSong()
        channel.answer = { part, _ ->
            part.appendBytes(CONTENT.copyOf(3))
            throw FileDoesNotFit(totalBytes = 21_569_325)
        }

        assertEquals(DownloadOutcome.NoSpace(totalBytes = 21_569_325), fetcher.fetch(download))

        assertNull(db.downloadDao().get(download.sourceUid))
        assertFalse(storage.phonePartFileFor(download.sourceUid).exists())
        assertFalse(storage.phoneLengthFileFor(download.sourceUid).exists())
    }

    @Test
    fun `a song removed during the transfer is cancelled and leaves nothing behind`() = runTest {
        val download = queuedSong()
        channel.answer = { part, onProgress ->
            part.appendBytes(CONTENT.copyOf(3))
            // The selection changed on the phone meanwhile.
            db.downloadDao().delete(listOf(download.sourceUid))
            onProgress(3, CONTENT.size.toLong())
            FileReceive.Received(3)
        }

        assertEquals(DownloadOutcome.Cancelled, fetcher.fetch(download))

        assertFalse(storage.phonePartFileFor(download.sourceUid).exists())
    }

    @Test
    fun `a stopped job puts the song back into the queue`() = runTest {
        val download = queuedSong()
        channel.answer = { _, _ -> throw CancellationException("job stopped") }

        try {
            fetcher.fetch(download)
            fail("cancellation must go on")
        } catch (_: CancellationException) {
        }

        assertEquals(DownloadState.QUEUED, db.downloadDao().get(download.sourceUid)!!.state)
    }

    // --- Episodes and books ---

    private suspend fun spoken(kind: BookKind, files: Int = 1): Long {
        val bookId = db.bookDao().upsertBook(
            BookEntity(serverId = if (kind == BookKind.EPISODE) "feed-1" else "abs-1", kind = kind, remoteId = "item-1", title = "Item", normTitle = "item", author = "", durationMs = 1_000, detailsLoaded = true),
        )
        db.bookDao().replaceDetails(bookId, (0 until files).map { BookTrackEntity(bookId, it, "file-$it", it * 1_000L, 1_000, "audio/mpeg") }, emptyList())
        return bookId
    }

    private suspend fun trackState(bookId: Long, index: Int = 0) = db.bookDao().tracks(bookId)[index].downloadState

    @Test
    fun `an episode from the phone is stored and marked done`() = runTest {
        val bookId = spoken(BookKind.EPISODE)

        assertEquals(DownloadOutcome.Done, fetcher.fetchEpisode(db.bookDao().tracks(bookId).single()))

        assertEquals(listOf(WearEpisodeFile.uid("feed-1", "item-1")), channel.requested)
        val track = db.bookDao().tracks(bookId).single()
        assertEquals(TrackDownload.DONE, track.downloadState)
        assertArrayEquals(CONTENT, File(track.filePath!!).readBytes())
    }

    @Test
    fun `an episode that does not fit leaves the queue and names its size`() = runTest {
        val bookId = spoken(BookKind.EPISODE)
        channel.answer = { _, _ -> throw FileDoesNotFit(totalBytes = 50_000_000) }

        assertEquals(DownloadOutcome.NoSpace(totalBytes = 50_000_000), fetcher.fetchEpisode(db.bookDao().tracks(bookId).single()))

        assertEquals(TrackDownload.NONE, trackState(bookId))
        assertFalse(episodeStorage.phonePartFileFor(bookId).exists())
    }

    @Test
    fun `a book file from the phone replaces a started server transfer`() = runTest {
        val bookId = spoken(BookKind.BOOK, files = 2)
        bookStorage.partFileFor(bookId, 1).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(10))

        assertEquals(DownloadOutcome.Done, fetcher.fetchBookTrack(db.bookDao().tracks(bookId)[1]))

        assertEquals(listOf(WearBookFile.uid("abs-1", "item-1", 1)), channel.requested)
        assertFalse(bookStorage.partFileFor(bookId, 1).exists())
        assertEquals(TrackDownload.DONE, trackState(bookId, 1))
        assertArrayEquals(CONTENT, File(db.bookDao().tracks(bookId)[1].filePath!!).readBytes())
    }

    @Test
    fun `a book file the phone does not have fails the book`() = runTest {
        val bookId = spoken(BookKind.BOOK)
        channel.answer = { _, _ -> FileReceive.Missing }

        assertEquals(DownloadOutcome.Failed(PhoneFileFetcher.MISSING_ON_PHONE), fetcher.fetchBookTrack(db.bookDao().tracks(bookId).single()))

        assertEquals(TrackDownload.FAILED, trackState(bookId))
    }

    @Test
    fun `a cover comes into memory, none without phone or image`() = runTest {
        val bookId = spoken(BookKind.EPISODE)

        assertArrayEquals(CONTENT, fetcher.fetchCover(bookId))
        assertEquals(WearCoverFile.uid("feed-1", "item-1"), channel.requested.last())

        val withoutImage = db.bookDao().upsertBook(
            BookEntity(serverId = "feed-1", kind = BookKind.EPISODE, remoteId = "missing", title = "No image", normTitle = "no image", author = "", durationMs = 1_000),
        )
        assertNull(fetcher.fetchCover(withoutImage))
        assertNull(fetcher.fetchCover(bookId = 9_999))

        channel.nodeId = null
        assertNull(fetcher.fetchCover(bookId))
    }

    private companion object {
        val CONTENT = "audio from the phone".toByteArray()
    }
}
