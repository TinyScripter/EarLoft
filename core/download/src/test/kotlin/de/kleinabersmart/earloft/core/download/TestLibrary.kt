package de.kleinabersmart.earloft.core.download

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** In-Memory-Bibliothek mit Quellen, Alben, Songs und Song-Quellen für Download-Tests. */
internal class TestLibrary {
    val db: EarLoftDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        EarLoftDatabase::class.java,
    ).allowMainThreadQueries().build()

    suspend fun source(sourceId: String, type: SourceType = SourceType.SUBSONIC, priority: Int = 0) {
        db.sourceDao().upsert(SourceEntity(sourceId = sourceId, type = type, name = sourceId, priority = priority))
    }

    suspend fun album(title: String): Long = db.libraryDao().insertAlbum(
        AlbumEntity(
            title = title,
            sortTitle = title,
            normTitle = title.lowercase(),
            albumArtistDisplay = "Band",
            normAlbumArtist = "band",
        ),
    )

    suspend fun song(albumId: Long, title: String, vararg sources: Pair<String, String>, dateAdded: Long = 0): Long {
        val songId = db.libraryDao().insertSong(
            SongEntity(
                albumId = albumId,
                title = title,
                normTitle = title.lowercase(),
                artistDisplay = "Band",
                durationMs = 1_000,
                dateAdded = dateAdded,
            ),
        )
        sources.forEach { (sourceId, remoteId) ->
            db.libraryDao().insertSongSource(
                SongSourceEntity(
                    uid = "$sourceId:$remoteId",
                    songId = songId,
                    sourceId = sourceId,
                    remoteId = remoteId,
                    mimeType = "audio/flac",
                    seenInGeneration = 1,
                ),
            )
        }
        return songId
    }

    fun close() = db.close()
}

/** Server, dessen Streams unter [baseUrl]`/stream/<remoteId>` liegen. */
internal open class FakeServer(
    override val sourceId: String,
    protected val baseUrl: String,
) : MusicDataSource {
    override val type = SourceType.SUBSONIC
    override val displayName = sourceId

    override fun fullScan(): Flow<SourceBatch> = emptyFlow()

    override suspend fun resolvePlayback(remoteId: String) =
        PlaybackLocation.Remote(Uri.parse("$baseUrl/stream/$remoteId"), cacheKey = "$sourceId:$remoteId")

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
}

/** [FakeServer], der auf Wunsch umwandelt: `/stream/<remoteId>?codec=…&maxKbps=…`. */
internal class TranscodingServer(sourceId: String, baseUrl: String) : FakeServer(sourceId, baseUrl), RemoteTranscoding {
    override fun transcodedPlayback(remoteId: String, codec: String, maxKbps: Int) = PlaybackLocation.Remote(
        Uri.parse("$baseUrl/stream/$remoteId?codec=$codec&maxKbps=$maxKbps"),
        cacheKey = "$sourceId:$remoteId:$codec-$maxKbps",
    )
}

internal class FakeSources(private vararg val sources: MusicDataSource) : MusicDataSources {
    override suspend fun all(): List<MusicDataSource> = sources.toList()
    override suspend fun get(sourceId: String): MusicDataSource? = sources.firstOrNull { it.sourceId == sourceId }
}

internal class RecordingScheduler : DownloadScheduler {
    var calls = 0

    override suspend fun enqueue(replace: Boolean) {
        calls++
    }
}
