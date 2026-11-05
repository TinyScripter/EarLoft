package de.kleinabersmart.tinyamp.core.datasource.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.datasource.LocalLyrics
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.PlaybackLocation
import de.kleinabersmart.tinyamp.core.datasource.R
import de.kleinabersmart.tinyamp.core.datasource.RawTrack
import de.kleinabersmart.tinyamp.core.datasource.SourceBatch
import de.kleinabersmart.tinyamp.core.datasource.decodeSampled
import de.kleinabersmart.tinyamp.core.model.LrcParser
import de.kleinabersmart.tinyamp.core.model.Lyrics
import de.kleinabersmart.tinyamp.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Musik auf dem Gerät, gelesen über MediaStore (Konzept 6.2). */
@Singleton
class LocalDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MusicDataSource, LocalLyrics {

    private val tagReader = LocalTagReader(context)

    override val sourceId: String = SOURCE_ID
    override val type: SourceType = SourceType.LOCAL
    override val displayName: String get() = context.getString(R.string.local_source_name)

    override fun fullScan(): Flow<SourceBatch> = fullScan { _, _ -> false }

    override fun fullScan(isUnchanged: suspend (String, Long) -> Boolean): Flow<SourceBatch> = flow {
        // Ohne Berechtigung liefert MediaStore eine leere Liste statt eines Fehlers – der
        // Abgleich würde dann die ganze Bibliothek löschen.
        if (!AudioPermission.isGranted(context)) throw SecurityException("${AudioPermission.name} fehlt")
        val rows = queryRows()
        MediaStoreTrackMapper.map(rows, FallbackNames.from(context))
            .chunked(BATCH_SIZE)
            .forEach { emit(SourceBatch(withTags(it, isUnchanged))) }
    }.flowOn(Dispatchers.IO)

    /** Liest die Tags nur für neue oder geänderte Dateien (mit wenigen Lesevorgängen gleichzeitig). */
    private suspend fun withTags(
        tracks: List<RawTrack>,
        isUnchanged: suspend (String, Long) -> Boolean,
    ): List<RawTrack> = coroutineScope {
        val limit = Semaphore(TAG_PARALLELISM)
        tracks.map { track ->
            async {
                if (track.lastModified?.let { isUnchanged(track.remoteId, it) } == true) {
                    track
                } else {
                    val tags = limit.withPermit { tagReader.read(contentUri(track.remoteId)) }
                    // Nicht lesbar: ohne Tags weiter – lastModified bleibt, die Datei wird nicht bei jedem Lauf neu versucht.
                    if (tags == null) track else MediaStoreTrackMapper.applyTags(track, tags)
                }
            }
        }.awaitAll()
    }

    override suspend fun embeddedLyrics(remoteId: String): Lyrics = withContext(Dispatchers.IO) {
        val text = tagReader.read(contentUri(remoteId))?.lyrics ?: return@withContext Lyrics.None
        LrcParser.parse(text)
    }

    override suspend fun resolvePlayback(remoteId: String): PlaybackLocation =
        PlaybackLocation.LocalFile(contentUri(remoteId))

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val uri = contentUri(remoteId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } else {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, uri)
                        embeddedPicture?.let { decodeSampled(it, sizePx) }
                    } finally {
                        release()
                    }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // MediaMetadataRetriever wirft bei defekten Dateien IllegalArgumentException/RuntimeException.
            null
        }
    }

    private fun contentUri(remoteId: String) =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, remoteId.toLong())

    private fun queryRows(): List<MediaStoreRow> {
        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.COMPILATION)
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.BITRATE)
            }
        }
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns.toTypedArray(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        ) ?: throw IOException("MediaStore-Abfrage lieferte keinen Cursor")

        return cursor.use { c ->
            buildList(c.count) {
                while (c.moveToNext()) {
                    add(
                        MediaStoreRow(
                            id = c.long(MediaStore.Audio.Media._ID) ?: continue,
                            title = c.string(MediaStore.Audio.Media.TITLE),
                            artist = c.string(MediaStore.Audio.Media.ARTIST),
                            album = c.string(MediaStore.Audio.Media.ALBUM),
                            albumId = c.long(MediaStore.Audio.Media.ALBUM_ID) ?: 0,
                            albumArtist = c.string("album_artist"),
                            isCompilation = c.string("compilation") == "1",
                            durationMs = c.long(MediaStore.Audio.Media.DURATION) ?: 0,
                            track = c.long(MediaStore.Audio.Media.TRACK)?.toInt() ?: 0,
                            year = c.long(MediaStore.Audio.Media.YEAR)?.toInt() ?: 0,
                            genre = c.string("genre"),
                            mimeType = c.string(MediaStore.Audio.Media.MIME_TYPE),
                            bitRate = c.long("bitrate")?.toInt(),
                            sizeBytes = c.long(MediaStore.Audio.Media.SIZE) ?: 0,
                            dateAddedSec = c.long(MediaStore.Audio.Media.DATE_ADDED) ?: 0,
                            dateModifiedSec = c.long(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0,
                        ),
                    )
                }
            }
        }
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.long(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    companion object {
        const val SOURCE_ID = "local"
        private const val BATCH_SIZE = 500
        private const val TAG_PARALLELISM = 4
    }
}
