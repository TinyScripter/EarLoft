package de.kleinabersmart.tinyamp.core.download

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.data.source.MusicDataSources
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.entity.AlbumEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongSourceEntity
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.PlaybackLocation
import de.kleinabersmart.tinyamp.core.datasource.SourceBatch
import de.kleinabersmart.tinyamp.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** In-Memory-Bibliothek mit Quellen, Alben, Songs und Song-Quellen für Download-Tests. */
internal class TestLibrary {
    val db: TinyAmpDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TinyAmpDatabase::class.java,
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

    suspend fun song(albumId: Long, title: String, vararg sources: Pair<String, String>): Long {
        val songId = db.libraryDao().insertSong(
            SongEntity(
                albumId = albumId,
                title = title,
                normTitle = title.lowercase(),
                artistDisplay = "Band",
                durationMs = 1_000,
                dateAdded = 0,
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
internal class FakeServer(
    override val sourceId: String,
    private val baseUrl: String,
) : MusicDataSource {
    override val type = SourceType.SUBSONIC
    override val displayName = sourceId

    override fun fullScan(): Flow<SourceBatch> = emptyFlow()

    override suspend fun resolvePlayback(remoteId: String) =
        PlaybackLocation.Remote(Uri.parse("$baseUrl/stream/$remoteId"), cacheKey = "$sourceId:$remoteId")

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
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
