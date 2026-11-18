package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.datasource.ExternalPlayback
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CastMediaResolverTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var resolver: CastMediaResolver
    private var albumId = 0L
    private val phone = FakePhone()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        resolver = CastMediaResolver(db.libraryDao(), db.downloadDao(), Sources, phone)
        db.sourceDao().upsert(SourceEntity("local", SourceType.LOCAL, "local"))
        db.sourceDao().upsert(SourceEntity("nd", SourceType.SUBSONIC, "nd", priority = 0))
        db.sourceDao().upsert(SourceEntity("nd2", SourceType.SUBSONIC, "nd2", priority = 5))
        albumId = db.libraryDao().insertAlbum(AlbumEntity(title = "A", sortTitle = "A", normTitle = "a", albumArtistDisplay = "B", normAlbumArtist = "b"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `local-only songs come from the phone, local copies of server songs stream from the server`() = runTest {
        val localOnly = song("local" to "9")
        val both = song("local" to "8", "nd" to "1")

        val media = resolver.resolve(listOf(localOnly, both))

        assertEquals("phone:content://media/9 audio/mpeg", media[localOnly]?.streamUrl)
        assertEquals("phone-cover:$albumId", media[localOnly]?.coverUrl)
        assertEquals(PhoneCastServer.SOURCE_ID, media[localOnly]?.sourceId)
        assertEquals("http://nd/stream/1", media[both]?.streamUrl)
        assertEquals("http://nd/cover/1", media[both]?.coverUrl)
    }

    @Test
    fun `without an address on the network the phone serves nothing`() = runTest {
        val localOnly = song("local" to "9")
        phone.online = false

        assertNull(resolver.resolve(listOf(localOnly))[localOnly])
    }

    @Test
    fun `local files the receiver cannot play are left out`() = runTest {
        val wma = song("local" to "9", mimeType = "audio/x-ms-wma")

        assertNull(resolver.resolve(listOf(wma))[wma])
    }

    @Test
    fun `a download plays from the phone when the receiver cannot reach its server`() = runTest {
        val songId = song("nd" to "1", mimeType = "audio/flac")
        val file = File.createTempFile("download", ".mp3").apply { deleteOnExit() }
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity(sourceUid = "nd:1", songId = songId, state = DownloadState.DONE, filePath = file.path)))

        assertEquals("http://nd/stream/1", resolver.resolve(listOf(songId))[songId]?.streamUrl)
        val fromPhone = resolver.resolve(listOf(songId), excludedSources = setOf("nd"))[songId]
        assertEquals("phone:${Uri.fromFile(file)} audio/mpeg", fromPhone?.streamUrl)
        assertEquals("audio/mpeg", fromPhone?.mimeType)
        assertNull(resolver.resolve(listOf(songId), excludedSources = setOf("nd", PhoneCastServer.SOURCE_ID))[songId])
    }

    @Test
    fun `server priority decides between servers`() = runTest {
        val songId = song("nd" to "1", "nd2" to "2")

        assertEquals("http://nd2/stream/2", resolver.resolve(listOf(songId))[songId]?.streamUrl)
    }

    @Test
    fun `a server the receiver could not reach is skipped`() = runTest {
        val both = song("nd" to "1", "nd2" to "2")
        val onlyExcluded = song("nd2" to "3")

        val media = resolver.resolve(listOf(both, onlyExcluded), excludedSources = setOf("nd2"))

        assertEquals("http://nd/stream/1", media[both]?.streamUrl)
        assertEquals("nd", media[both]?.sourceId)
        assertNull(media[onlyExcluded])
    }

    @Test
    fun `unsupported or unknown formats are transcoded to mp3`() = runTest {
        val flac = song("nd" to "1", mimeType = "audio/flac")
        val wma = song("nd" to "2", mimeType = "audio/x-ms-wma")
        val unknown = song("nd" to "3", mimeType = null)

        val media = resolver.resolve(listOf(flac, wma, unknown))

        assertEquals("audio/flac", media[flac]?.mimeType)
        assertEquals("http://nd/stream/1", media[flac]?.streamUrl)
        assertEquals("audio/mpeg", media[wma]?.mimeType)
        assertEquals("http://nd/stream/2?format=mp3&maxBitRate=320", media[wma]?.streamUrl)
        assertEquals("http://nd/stream/3?format=mp3&maxBitRate=320", media[unknown]?.streamUrl)
    }

    private suspend fun song(vararg sources: Pair<String, String>, mimeType: String? = "audio/mpeg"): Long {
        val songId = db.libraryDao().insertSong(
            SongEntity(albumId = albumId, title = "S", normTitle = "s", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        sources.forEach { (sourceId, remoteId) ->
            db.libraryDao().insertSongSource(
                SongSourceEntity(
                    uid = "$sourceId:$remoteId", songId = songId, sourceId = sourceId, remoteId = remoteId,
                    mimeType = mimeType, seenInGeneration = 1,
                ),
            )
        }
        return songId
    }

    private class FakePhone : PhoneCastServer {
        var online = true
        override fun fileUrl(uri: Uri, mimeType: String) = if (online) "phone:$uri $mimeType" else null
        override fun albumCoverUrl(albumId: Long) = if (online) "phone-cover:$albumId" else null
        override fun bookCoverUrl(bookId: Long) = null
    }

    private object Sources : MusicDataSources {
        private fun local() = object : MusicDataSource {
            override val sourceId = "local"
            override val type = SourceType.LOCAL
            override val displayName = "local"
            override fun fullScan(): Flow<SourceBatch> = emptyFlow()
            override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.parse("content://media/$remoteId"))
            override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
        }

        private class Server(override val sourceId: String) : MusicDataSource, ExternalPlayback {
            override val type = SourceType.SUBSONIC
            override val displayName = sourceId
            override fun fullScan(): Flow<SourceBatch> = emptyFlow()
            override suspend fun resolvePlayback(remoteId: String) =
                PlaybackLocation.Remote(Uri.parse("http://$sourceId/stream/$remoteId"), "$sourceId:$remoteId")
            override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
            override fun externalStreamUrl(remoteId: String, transcodeTo: Pair<String, Int>?) =
                "http://$sourceId/stream/$remoteId" + transcodeTo?.let { (format, kbps) -> "?format=$format&maxBitRate=$kbps" }.orEmpty()
            override fun externalCoverUrl(coverRemoteId: String, sizePx: Int) = "http://$sourceId/cover/$coverRemoteId"
        }

        private val all = listOf(local(), Server("nd"), Server("nd2"))

        override suspend fun all() = all
        override suspend fun get(sourceId: String) = all.firstOrNull { it.sourceId == sourceId }
    }
}
