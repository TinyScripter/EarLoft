package de.kleinabersmart.earloft.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/** Removing a song download or a favorite offers Undo; the song file stays until the snackbar expires (review5 P58). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class OfflineAndFavoritesUndoTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var downloadStorage: DownloadStorage
    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var mergeDao: MergeDao
    @Inject lateinit var sourceDao: SourceDao
    @Inject lateinit var snackbars: Snackbars

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appScope = CoroutineScope(Dispatchers.Unconfined)

    private val offlineViewModel by lazy { OfflineViewModel(downloadRepository, snackbars, context, appScope) }
    private val favoritesViewModel by lazy { CollectionFavoritesViewModel(favoritesRepository, snackbars, context, appScope) }

    @Before
    fun setUp() {
        hilt.inject()
    }

    private fun TestScope.collectMessages(): Channel<SnackbarMessage> {
        val channel = Channel<SnackbarMessage>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snackbars.messages.collect { channel.trySend(it) } }
        return channel
    }

    private suspend fun insertDownloadedSong(title: String, state: DownloadState = DownloadState.DONE): Pair<Long, File> {
        sourceDao.upsert(SourceEntity("home", SourceType.SUBSONIC, "Home"))
        val albumId = mergeDao.insertAlbum(AlbumEntity(title = "Takk", sortTitle = "Takk", normTitle = "takk", albumArtistDisplay = "A", normAlbumArtist = "a"))
        val songId = mergeDao.insertSong(
            SongEntity(albumId = albumId, title = title, normTitle = title.lowercase(), artistDisplay = "A", durationMs = 1_000, dateAdded = 0),
        )
        val uid = "home:$songId"
        mergeDao.insertSongSource(SongSourceEntity(uid = uid, songId = songId, sourceId = "home", remoteId = "$songId", seenInGeneration = 1))
        val file = downloadStorage.fileFor(uid, "audio/flac").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(16))
        }
        downloadDao.insertIfAbsent(listOf(DownloadEntity(uid, songId, state, filePath = file.absolutePath, pinnedBy = "manual")))
        return songId to file
    }

    @Test
    fun `removing a downloaded song defers file deletion until snackbar expiry and undo keeps the file`() = runTest {
        val (songId, file) = insertDownloadedSong("Hoppípolla")
        val messages = collectMessages()

        offlineViewModel.removeDownload(songId, "Hoppípolla", downloaded = true)

        // Hidden immediately in UI, file and DB row stay intact while the Undo snackbar is visible.
        assertTrue(songId in offlineViewModel.pendingRemovals.value)
        assertTrue(file.exists())
        assertNotNull(downloadDao.get("home:$songId"))
        val undoMessage = awaitReal { messages.receive() }
        assertTrue(undoMessage.text, "Hoppípolla" in undoMessage.text)
        assertNotNull(undoMessage.actionLabel)

        // Undo brings it back without re-downloading.
        undoMessage.onAction!!.invoke()
        assertFalse(songId in offlineViewModel.pendingRemovals.value)
        assertTrue(file.exists())
        assertNotNull(downloadDao.get("home:$songId"))

        // Remove again and let the snackbar expire: now the DB row and file are deleted.
        offlineViewModel.removeDownload(songId, "Hoppípolla", downloaded = true)
        awaitReal { messages.receive() }.onDismissed!!.invoke()
        awaitReal { while (downloadDao.get("home:$songId") != null || file.exists()) delay(10.milliseconds) }
        assertNull(downloadDao.get("home:$songId"))
        assertFalse(file.exists())
    }

    @Test
    fun `requesting download while removal is pending withdraws the snackbar and keeps the file`() = runTest {
        val (songId, file) = insertDownloadedSong("Glósóli")
        val messages = collectMessages()
        val withdrawn = Channel<SnackbarMessage>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snackbars.withdrawn.collect { withdrawn.trySend(it) } }

        offlineViewModel.removeDownload(songId, "Glósóli", downloaded = true)
        val shown = awaitReal { messages.receive() }
        offlineViewModel.download(songId)

        assertFalse(songId in offlineViewModel.pendingRemovals.value)
        assertEquals(shown, awaitReal { withdrawn.receive() })
        assertTrue(file.exists())
        assertNotNull(downloadDao.get("home:$songId"))
    }

    @Test
    fun `cancelling a queued download removes it right away without waiting for a snackbar`() = runTest {
        val (songId, _) = insertDownloadedSong("Sæglópur", state = DownloadState.QUEUED)
        val messages = collectMessages()

        offlineViewModel.removeDownload(songId, "Sæglópur", downloaded = false)

        awaitReal { while (downloadDao.get("home:$songId") != null) delay(10.milliseconds) }
        assertTrue(messages.tryReceive().isFailure)
    }

    @Test
    fun `removing an album or artist favorite offers undo that restores it`() = runTest {
        val albumId = mergeDao.insertAlbum(AlbumEntity(title = "Takk", sortTitle = "Takk", normTitle = "takk", albumArtistDisplay = "A", normAlbumArtist = "a"))
        val artistId = mergeDao.insertArtist(ArtistEntity(name = "Sigur Rós", sortName = "Sigur Rós", normName = "sigur ros"))
        favoritesRepository.setCollectionFavorite(FavoriteKind.ALBUM, albumId, true)
        favoritesRepository.setCollectionFavorite(FavoriteKind.ARTIST, artistId, true)
        val messages = collectMessages()

        favoritesViewModel.toggle(FavoriteKind.ALBUM, albumId, "Takk")
        val albumUndo = awaitReal { messages.receive() }
        assertFalse(favoritesRepository.isAlbumFavorite(albumId).first())
        assertTrue(albumUndo.text, "Takk" in albumUndo.text)
        assertNotNull(albumUndo.actionLabel)

        albumUndo.onAction!!.invoke()
        awaitReal { favoritesRepository.isAlbumFavorite(albumId).first { it } }

        favoritesViewModel.toggle(FavoriteKind.ARTIST, artistId, "Sigur Rós")
        val artistUndo = awaitReal { messages.receive() }
        assertFalse(favoritesRepository.isArtistFavorite(artistId).first())
        artistUndo.onAction!!.invoke()
        awaitReal { favoritesRepository.isArtistFavorite(artistId).first { it } }
    }
}
