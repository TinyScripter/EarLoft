package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.ReplayGain
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSourceResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: EarLoftDatabase
    private lateinit var resolver: PlaybackSourceResolver
    private val policy = PlaybackPolicy()
    /** Was [MobileStreaming] gerade verlangt; `null`: Original (WLAN bzw. Einstellung „Original“). */
    private var transcodeTo: Pair<String, Int>? = null
    private var albumId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mobile = object : MobileStreaming(context, LibrarySettings(context)) {
            override suspend fun transcodeTo() = this@PlaybackSourceResolverTest.transcodeTo
        }
        resolver = PlaybackSourceResolver(db.libraryDao(), db.downloadDao(), Sources, policy, mobile)
        db.sourceDao().upsert(SourceEntity("local", SourceType.LOCAL, "local"))
        db.sourceDao().upsert(SourceEntity("nd", SourceType.SUBSONIC, "nd"))
        albumId = db.libraryDao().insertAlbum(AlbumEntity(title = "A", sortTitle = "A", normTitle = "a", albumArtistDisplay = "B", normAlbumArtist = "b"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `finished download beats the stream`() = runTest {
        val songId = song("nd" to "1")
        val file = tempFolder.newFile("nd_1.flac")
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:1", songId, DownloadState.DONE, filePath = file.path)))

        assertEquals(PlaybackLocation.LocalFile(Uri.fromFile(file)), resolver.candidates(songId).firstOrNull()?.location)
    }

    @Test
    fun `missing download file falls back to the stream`() = runTest {
        val songId = song("nd" to "1")
        db.downloadDao().insertIfAbsent(
            listOf(DownloadEntity("nd:1", songId, DownloadState.DONE, filePath = "/gibt/es/nicht.flac")),
        )

        assertEquals(Sources.stream("nd", "1"), resolver.candidates(songId).firstOrNull()?.location)
    }

    @Test
    fun `unfinished download is not played`() = runTest {
        val songId = song("nd" to "1")
        val file = tempFolder.newFile("nd_1.part")
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:1", songId, DownloadState.RUNNING, filePath = file.path)))

        assertEquals(Sources.stream("nd", "1"), resolver.candidates(songId).firstOrNull()?.location)
    }

    @Test
    fun `without streaming only finished downloads are played`() = runTest {
        policy.allowStreaming = false
        val streamOnly = song("nd" to "1")
        assertEquals(emptyList<ResolvedPlayback>(), resolver.candidates(streamOnly))

        val downloaded = song("nd" to "2")
        val file = tempFolder.newFile("nd_2.ogg")
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:2", downloaded, DownloadState.DONE, filePath = file.path)))
        assertEquals(
            listOf<PlaybackLocation>(PlaybackLocation.LocalFile(Uri.fromFile(file))),
            resolver.candidates(downloaded).map { it.location },
        )
    }

    @Test
    fun `local file beats everything`() = runTest {
        val songId = song("nd" to "1", "local" to "9")

        assertEquals(Sources.local("9"), resolver.candidates(songId).firstOrNull()?.location)
    }

    @Test
    fun `all candidates are listed for fallback, best first`() = runTest {
        val songId = song("nd" to "1", "local" to "9")
        val file = tempFolder.newFile("nd_1.flac")
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:1", songId, DownloadState.DONE, filePath = file.path)))

        val candidates = resolver.candidates(songId)
        assertEquals(
            listOf(Sources.local("9"), PlaybackLocation.LocalFile(Uri.fromFile(file)), Sources.stream("nd", "1")),
            candidates.map { it.location },
        )
        assertEquals(listOf("local", null, "nd"), candidates.map { it.sourceId })
    }

    @Test
    fun `replay gain comes from the chosen source`() = runTest {
        val serverValues = ReplayGain(trackGainDb = -6.5f, albumPeak = 0.9f)
        // Die lokale Datei gewinnt; ihre Werte kennt die Datenbank (noch) nicht.
        val local = song("nd" to "1", "local" to "9", replayGain = mapOf("nd" to serverValues))
        assertNull(resolver.candidates(local).firstOrNull()?.replayGain)

        val streamed = song("nd" to "2", replayGain = mapOf("nd" to serverValues))
        assertEquals(serverValues, resolver.candidates(streamed).firstOrNull()?.replayGain)
    }

    @Test
    fun `over mobile data the converted stream comes first, the original stays as fallback`() = runTest {
        transcodeTo = "opus" to 128
        val songId = song("nd" to "1", "local" to "9", bitRate = mapOf("nd" to 320_000))

        val candidates = resolver.candidates(songId)
        assertEquals(
            listOf(Sources.local("9"), Sources.transcoded("nd", "1", "opus", 128), Sources.stream("nd", "1")),
            candidates.map { it.location },
        )
        assertEquals(listOf(null, "opus" to 128, null), candidates.map { it.transcodedTo })

        // In WLAN (bzw. mit „Original“) bleibt es beim Original.
        transcodeTo = null
        assertEquals(listOf(null, null), resolver.candidates(songId).map { it.transcodedTo })
    }

    @Test
    fun `songs that would hardly shrink are not converted`() = runTest {
        transcodeTo = "opus" to 128
        val small = song("nd" to "1", bitRate = mapOf("nd" to 135_000))
        assertEquals(listOf(Sources.stream("nd", "1")), resolver.candidates(small).map { it.location })
        // Ab dem 1,25-Fachen lohnt es sich.
        val mp3 = song("nd" to "3", bitRate = mapOf("nd" to 192_000))
        assertEquals("opus" to 128, resolver.candidates(mp3).first().transcodedTo)

        // Ohne bekannte Bitrate wird umgewandelt.
        val unknown = song("nd" to "2")
        assertEquals("opus" to 128, resolver.candidates(unknown).first().transcodedTo)
    }

    private suspend fun song(
        vararg sources: Pair<String, String>,
        replayGain: Map<String, ReplayGain> = emptyMap(),
        bitRate: Map<String, Int> = emptyMap(),
    ): Long {
        val songId = db.libraryDao().insertSong(
            SongEntity(albumId = albumId, title = "S", normTitle = "s", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        sources.forEach { (sourceId, remoteId) ->
            val rg = replayGain[sourceId]
            db.libraryDao().insertSongSource(
                SongSourceEntity(
                    uid = "$sourceId:$remoteId", songId = songId, sourceId = sourceId, remoteId = remoteId, seenInGeneration = 1,
                    rgTrackGainDb = rg?.trackGainDb, rgTrackPeak = rg?.trackPeak,
                    rgAlbumGainDb = rg?.albumGainDb, rgAlbumPeak = rg?.albumPeak, bitRate = bitRate[sourceId],
                ),
            )
        }
        return songId
    }

    private object Sources : MusicDataSources {
        fun stream(sourceId: String, remoteId: String) =
            PlaybackLocation.Remote(Uri.parse("http://$sourceId/stream/$remoteId"), "$sourceId:$remoteId")

        fun transcoded(sourceId: String, remoteId: String, codec: String, kbps: Int) =
            PlaybackLocation.Remote(Uri.parse("http://$sourceId/stream/$remoteId?format=$codec"), "$sourceId:$remoteId:$codec-$kbps")

        fun local(remoteId: String) = PlaybackLocation.LocalFile(Uri.parse("content://media/$remoteId"))

        private open class Source(override val sourceId: String, override val type: SourceType) : MusicDataSource {
            override val displayName = sourceId
            override fun fullScan(): Flow<SourceBatch> = emptyFlow()
            override suspend fun resolvePlayback(remoteId: String) =
                if (type == SourceType.LOCAL) local(remoteId) else stream(sourceId, remoteId)
            override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
        }

        /** Der Server kann umwandeln, die Geräte-Quelle nicht. */
        private class Server(sourceId: String) : Source(sourceId, SourceType.SUBSONIC), RemoteTranscoding {
            override fun transcodedPlayback(remoteId: String, codec: String, maxKbps: Int) = transcoded(sourceId, remoteId, codec, maxKbps)
        }

        private val all = listOf(Source("local", SourceType.LOCAL), Server("nd"))

        override suspend fun all() = all
        override suspend fun get(sourceId: String) = all.firstOrNull { it.sourceId == sourceId }
    }
}
