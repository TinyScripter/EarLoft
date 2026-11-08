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
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
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
    private var albumId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        resolver = PlaybackSourceResolver(db.libraryDao(), db.downloadDao(), Sources, policy)
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

    private suspend fun song(vararg sources: Pair<String, String>, replayGain: Map<String, ReplayGain> = emptyMap()): Long {
        val songId = db.libraryDao().insertSong(
            SongEntity(albumId = albumId, title = "S", normTitle = "s", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        sources.forEach { (sourceId, remoteId) ->
            val rg = replayGain[sourceId]
            db.libraryDao().insertSongSource(
                SongSourceEntity(
                    uid = "$sourceId:$remoteId", songId = songId, sourceId = sourceId, remoteId = remoteId, seenInGeneration = 1,
                    rgTrackGainDb = rg?.trackGainDb, rgTrackPeak = rg?.trackPeak,
                    rgAlbumGainDb = rg?.albumGainDb, rgAlbumPeak = rg?.albumPeak,
                ),
            )
        }
        return songId
    }

    private object Sources : MusicDataSources {
        fun stream(sourceId: String, remoteId: String) =
            PlaybackLocation.Remote(Uri.parse("http://$sourceId/stream/$remoteId"), "$sourceId:$remoteId")

        fun local(remoteId: String) = PlaybackLocation.LocalFile(Uri.parse("content://media/$remoteId"))

        private fun source(id: String, type: SourceType) = object : MusicDataSource {
            override val sourceId = id
            override val type = type
            override val displayName = id
            override fun fullScan(): Flow<SourceBatch> = emptyFlow()
            override suspend fun resolvePlayback(remoteId: String) =
                if (type == SourceType.LOCAL) local(remoteId) else stream(id, remoteId)
            override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
        }

        private val all = listOf(source("local", SourceType.LOCAL), source("nd", SourceType.SUBSONIC))

        override suspend fun all() = all
        override suspend fun get(sourceId: String) = all.firstOrNull { it.sourceId == sourceId }
    }
}
