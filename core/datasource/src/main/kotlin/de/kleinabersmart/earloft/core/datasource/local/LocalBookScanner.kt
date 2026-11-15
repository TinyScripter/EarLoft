package de.kleinabersmart.earloft.core.datasource.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.datasource.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Audiodatei eines lokalen Buchs. [uri] ist die `content://`-Adresse in MediaStore. */
data class LocalBookTrack(val uri: String, val startMs: Long, val durationMs: Long, val mimeType: String?)

data class LocalBookChapter(val title: String, val startMs: Long, val endMs: Long)

data class LocalBookDetails(
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val tracks: List<LocalBookTrack>,
    val chapters: List<LocalBookChapter>,
    /** Untertitel und Erscheinungsjahr aus den Tags der ersten Datei. */
    val subtitle: String? = null,
    val year: Int? = null,
    /**
     * Aus den Begleitdateien und Ordnernamen (`books_improvements.md` M7): Beschreibung, Serie im Format von Audiobookshelf
     * („Name #2“), Verlag, Sprache, ISBN, Genres und das Cover im Ordner (Adresse über die Ordnerfreigabe).
     */
    val description: String? = null,
    val series: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val genres: List<String> = emptyList(),
    val coverUri: String? = null,
)

/**
 * Ein Buch aus dem Ordner „Audiobooks“. [key] ist der Pfad ab „Audiobooks/“, [fingerprint] ändert sich mit den Dateien.
 * [details] fehlt, wenn der Aufrufer das Buch als unverändert bezeichnet hat (dann wurde nichts gelesen) oder sein Ordner
 * gerade nicht lesbar war (dann ist auch [fingerprint] ohne Bedeutung, und das Buch bleibt, wie es gespeichert ist).
 */
data class LocalBook(val key: String, val fingerprint: Long, val addedAtMs: Long, val details: LocalBookDetails?)

/** Eine Podcast-Datei. [details] fehlt, wenn der Aufrufer sie als unverändert bezeichnet hat. */
data class LocalEpisode(val key: String, val fingerprint: Long, val details: LocalEpisodeDetails?)

data class LocalEpisodeDetails(
    val title: String,
    val description: String,
    /** Erscheinungsdatum aus dem Tag, sonst der Zeitpunkt, an dem die Datei aufs Gerät kam (Unix-ms). */
    val publishedAtMs: Long,
    val track: LocalBookTrack,
    val chapters: List<LocalBookChapter>,
    /** Die Datei trägt ein eingebettetes Bild. */
    val hasCover: Boolean,
)

/** Ein Podcast aus dem Ordner „Podcasts“ mit seinen Folgen. */
data class LocalPodcast(val name: String, val episodes: List<LocalEpisode>)

/**
 * Findet Hörbücher und Podcast-Folgen auf dem Gerät über MediaStore (Konzept 12.1): `m4b` direkt in „Audiobooks“ und Ordner voller
 * MP3-Dateien darin. MediaStore genügt (kein SAF), weil die App die Audio-Berechtigung für die Musik ohnehin hat und
 * MediaStore Dateien in „Audiobooks“ als Audio führt – aber nicht als Musik, sodass sie nicht in der Bibliothek landen.
 */
@Singleton
class LocalBookScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** Freigabe des Ordners „Audiobooks“ für die Begleitdateien. */
    val folders: LocalBookFolders = LocalBookFolders(context),
) {
    private val tagReader = LocalTagReader(context)

    /**
     * Alle Bücher. Für unveränderte (laut [isUnchanged]) werden weder Tags noch Kapitel gelesen.
     * Ohne Berechtigung wirft das eine `SecurityException`: MediaStore lieferte sonst eine leere Liste, und der
     * Abgleich löschte alle Bücher samt Fortschritt.
     */
    suspend fun scan(isUnchanged: suspend (key: String, fingerprint: Long) -> Boolean): List<LocalBook> =
        withContext(Dispatchers.IO) {
            if (!AudioPermission.isGranted(context)) throw SecurityException("${AudioPermission.name} fehlt")
            val trees = folders.trees()
            LocalBookLayout.group(queryRows()).map { book ->
                val added = book.files.minOf { it.dateAddedSec } * 1000
                // Begleitdateien nur mit Ordnerfreigabe und nur bei Büchern in einem eigenen Ordner. Ist der Ordner gerade nicht
                // lesbar, sprängen Titel, Autor und Serie ohne sie bis zum nächsten Abgleich auf Tags und Ordnernamen.
                val sidecars = if (book.folderName != null && trees.isNotEmpty()) {
                    val files = folders.list(book.files.first().path.substringBeforeLast('/'), trees)
                        ?: return@map LocalBook(book.key, fingerprint = 0, added, details = null)
                    BookSidecars.pick(files)
                } else {
                    BookSidecars()
                }
                val fingerprint = 31 * LocalBookLayout.fingerprint(book) + sidecars.fingerprint
                LocalBook(book.key, fingerprint, added, if (isUnchanged(book.key, fingerprint)) null else read(book, sidecars))
            }
        }

    /**
     * Alle Podcasts aus „Podcasts“ (Unterordner oder Album-Tag, siehe [LocalPodcastLayout]); Fehler und Berechtigung wie bei
     * [scan]. Unveränderte Folgen (laut [isUnchanged], mit dem Namen ihres Podcasts) werden nicht gelesen.
     */
    suspend fun scanPodcasts(isUnchanged: suspend (podcast: String, key: String, fingerprint: Long) -> Boolean): List<LocalPodcast> =
        withContext(Dispatchers.IO) {
            if (!AudioPermission.isGranted(context)) throw SecurityException("${AudioPermission.name} fehlt")
            val untitled = context.getString(R.string.local_source_name)
            LocalPodcastLayout.group(queryRows(LocalPodcastLayout.ROOT), untitled).map { podcast ->
                LocalPodcast(
                    podcast.name,
                    podcast.files.map { file ->
                        val key = LocalPodcastLayout.key(file)
                        val fingerprint = LocalPodcastLayout.fingerprint(file)
                        LocalEpisode(key, fingerprint, if (isUnchanged(podcast.name, key, fingerprint)) null else readEpisode(file))
                    },
                )
            }
        }

    private suspend fun readEpisode(file: AudioFileRow): LocalEpisodeDetails {
        val uri = contentUri(file.id)
        val tags = tagReader.readSpoken(uri) ?: SpokenTags()
        // Eingebettete Kapitel (ID3 CHAP, sonst MP4) haben die genauen Grenzen; die Spanne reicht bis ans Dateiende.
        val chapters = tags.chapters.ifEmpty { (if (isMp4(file)) readMarks(uri) else emptyList()).toChapters(file.durationMs) }
        return LocalEpisodeDetails(
            title = file.title?.trim()?.takeIf { it.isNotEmpty() } ?: file.fileName.substringBeforeLast('.'),
            description = tags.description.orEmpty(),
            publishedAtMs = LocalPodcastLayout.parseTagDate(tags.date) ?: (file.dateAddedSec * 1000),
            track = LocalBookTrack(uri.toString(), 0, file.durationMs, file.mimeType),
            chapters = chapters,
            hasCover = withRetriever(uri) { it.embeddedPicture } != null,
        )
    }

    /** Eingebettetes Cover (Bilddatei) der Datei; `null`, wenn es keines gibt. */
    suspend fun cover(trackUri: String): ByteArray? = withContext(Dispatchers.IO) {
        withRetriever(Uri.parse(trackUri)) { it.embeddedPicture }
    }

    /** Cover aus dem Buchordner ([LocalBookDetails.coverUri]); `null`, wenn es nicht (mehr) lesbar ist. */
    suspend fun folderCover(uri: String): ByteArray? = withContext(Dispatchers.IO) { folders.read(uri, MAX_IMAGE_BYTES) }

    /**
     * Liest ein Buch. Rangfolge wie bei Audiobookshelf, jeweils das erste Vorhandene: `metadata.opf` vor `desc.txt` bzw.
     * `reader.txt` vor den Tags vor den Ordnernamen.
     */
    private suspend fun read(book: LocalBookFiles, sidecars: BookSidecars): LocalBookDetails {
        var start = 0L
        val tracks = mutableListOf<LocalBookTrack>()
        val chapters = mutableListOf<LocalBookChapter>()
        // Untertitel und Jahr stehen in jeder Datei gleich; ID3-Kapitel zählen nur bei einer einzelnen Datei.
        val tags = tagReader.readSpoken(contentUri(book.files.first().id))
        val multiFile = book.files.size > 1
        for (file in book.files) {
            val uri = contentUri(file.id)
            tracks += LocalBookTrack(uri.toString(), start, file.durationMs, file.mimeType)
            chapters += chaptersOf(file, uri, start, multiFile, tags?.chapters.orEmpty())
            start += file.durationMs
        }
        val composer = withRetriever(contentUri(book.files.first().id)) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER) }
        val folder = FolderMetadata.parse(book.key)
        val opf = sidecars.opf?.let(::readText)?.let(OpfMetadata::parse)
        val description = sidecars.description?.let(::readText)?.trim()?.ifEmpty { null }
        // Ein Sprecher je Zeile oder mit Komma getrennt.
        val reader = sidecars.reader?.let(::readText)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")?.ifEmpty { null }
        val tagSeries = tags?.series?.let { name -> tags.seriesPart?.let { "$name #$it" } ?: name }
        return LocalBookDetails(
            title = opf?.title ?: LocalBookLayout.bookTitle(book, folder.title),
            author = opf?.authors?.joinToString(", ")?.ifEmpty { null } ?: LocalBookLayout.bookAuthor(book).ifEmpty { folder.author.orEmpty() },
            narrator = opf?.narrators?.joinToString(", ")?.ifEmpty { null } ?: reader ?: composer?.trim()?.ifEmpty { null } ?: folder.narrator.orEmpty(),
            durationMs = start,
            tracks = tracks,
            chapters = chapters,
            subtitle = opf?.subtitle ?: tags?.subtitle,
            year = opf?.year ?: tags?.year ?: folder.year,
            description = opf?.description ?: description ?: tags?.description,
            series = opf?.seriesEntry ?: tagSeries ?: folder.seriesEntry,
            publisher = opf?.publisher,
            language = opf?.language,
            isbn = opf?.isbn,
            genres = opf?.genres.orEmpty(),
            coverUri = sidecars.cover?.uri,
        )
    }

    /** Text einer Begleitdatei: UTF-8, sonst (Windows-Editoren) Windows-1252. */
    private fun readText(file: SideFile): String? {
        val bytes = folders.read(file.uri, MAX_TEXT_BYTES) ?: return null
        val utf8 = String(bytes, Charsets.UTF_8)
        return (if ('\uFFFD' in utf8) String(bytes, charset("windows-1252")) else utf8).removePrefix("\uFEFF")
    }

    /**
     * Kapitel einer Datei als Position im Buch. Eingebettete Kapitel (m4b) haben Vorrang; eine einzelne MP3 nimmt ihre
     * ID3-Kapitel ([id3]); sonst ist bei einem Buch aus mehreren Dateien jede Datei ein Kapitel, und eine einzelne Datei
     * ohne Kapitel hat keine.
     */
    private fun chaptersOf(
        file: AudioFileRow,
        uri: Uri,
        offsetMs: Long,
        multiFile: Boolean,
        id3: List<LocalBookChapter>,
    ): List<LocalBookChapter> {
        val marks = if (isMp4(file)) readMarks(uri) else emptyList()
        if (marks.isNotEmpty()) return marks.toChapters(file.durationMs, offsetMs)
        if (!multiFile) return id3
        return listOf(LocalBookChapter(LocalBookLayout.chapterTitle(file.title, file.fileName), offsetMs, offsetMs + file.durationMs))
    }

    private fun isMp4(file: AudioFileRow): Boolean = isMp4(file.mimeType, file.fileName)

    private fun readMarks(uri: Uri): List<Mp4ChapterMark> = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream -> Mp4Chapters.read(ChannelSource(stream.channel)) }
        }.orEmpty()
    } catch (e: IOException) {
        Log.w(TAG, "Kapitel von $uri nicht lesbar", e)
        emptyList()
    }

    private fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (e: RuntimeException) {
            // Bei defekten Dateien wirft der Retriever IllegalArgumentException/RuntimeException.
            Log.w(TAG, "Metadaten von $uri nicht lesbar", e)
            null
        } finally {
            retriever.release()
        }
    }

    private fun contentUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    @Suppress("DEPRECATION") // DATA (der Pfad) ist der einzige Weg, das Verzeichnis auf allen unterstützten Versionen zu erkennen.
    private fun queryRows(root: String = LocalBookLayout.ROOT): List<AudioFileRow> {
        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns.toTypedArray(),
            "${MediaStore.Audio.Media.DATA} LIKE ?",
            arrayOf("%/$root/%"),
            null,
        ) ?: throw IOException("MediaStore-Abfrage lieferte keinen Cursor")
        return cursor.use { c ->
            buildList(c.count) {
                while (c.moveToNext()) {
                    add(
                        AudioFileRow(
                            id = c.long(MediaStore.Audio.Media._ID) ?: continue,
                            path = c.string(MediaStore.Audio.Media.DATA) ?: continue,
                            title = c.string(MediaStore.Audio.Media.TITLE),
                            artist = c.string(MediaStore.Audio.Media.ARTIST),
                            albumArtist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.string(MediaStore.Audio.Media.ALBUM_ARTIST) else null,
                            album = c.string(MediaStore.Audio.Media.ALBUM),
                            durationMs = c.long(MediaStore.Audio.Media.DURATION) ?: 0,
                            track = c.long(MediaStore.Audio.Media.TRACK)?.toInt() ?: 0,
                            mimeType = c.string(MediaStore.Audio.Media.MIME_TYPE),
                            sizeBytes = c.long(MediaStore.Audio.Media.SIZE) ?: 0,
                            dateAddedSec = c.long(MediaStore.Audio.Media.DATE_ADDED) ?: 0,
                            dateModifiedSec = c.long(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0,
                        ),
                    )
                }
            }
        }
    }

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private companion object {
        const val TAG = "LocalBookScanner"
        const val MAX_TEXT_BYTES = 512L * 1024
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}
