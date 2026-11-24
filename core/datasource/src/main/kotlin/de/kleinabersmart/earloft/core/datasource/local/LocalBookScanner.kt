package de.kleinabersmart.earloft.core.datasource.local

import de.kleinabersmart.earloft.core.datasource.MediaStoreUnavailableException
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

/** An audio file of a local book. [uri] is the `content://` address in MediaStore. */
data class LocalBookTrack(val uri: String, val startMs: Long, val durationMs: Long, val mimeType: String?)

data class LocalBookChapter(val title: String, val startMs: Long, val endMs: Long)

data class LocalBookDetails(
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val tracks: List<LocalBookTrack>,
    val chapters: List<LocalBookChapter>,
    /** Subtitle and publication year from the tags of the first file. */
    val subtitle: String? = null,
    val year: Int? = null,
    /**
     * From the companion files and folder names (`books_improvements.md` M7): description, series in Audiobookshelf's format
     * ("Name #2"), publisher, language, ISBN, genres and the cover in the folder (address via the folder grant).
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
 * A book from the "Audiobooks" folder. [key] is the path from "Audiobooks/", [fingerprint] changes with the files.
 * [details] is missing if the caller marked the book as unchanged (then nothing was read) or its folder
 * was unreadable at the moment (then [fingerprint] is meaningless too, and the book stays as stored).
 */
data class LocalBook(val key: String, val fingerprint: Long, val addedAtMs: Long, val details: LocalBookDetails?)

/** A podcast file. [details] is missing if the caller marked it as unchanged. */
data class LocalEpisode(val key: String, val fingerprint: Long, val details: LocalEpisodeDetails?)

data class LocalEpisodeDetails(
    val title: String,
    val description: String,
    /** Publication date from the tag, otherwise the time the file arrived on the device (Unix ms). */
    val publishedAtMs: Long,
    val track: LocalBookTrack,
    val chapters: List<LocalBookChapter>,
    /** The file carries an embedded image. */
    val hasCover: Boolean,
)

/** A podcast from the "Podcasts" folder with its episodes. */
data class LocalPodcast(val name: String, val episodes: List<LocalEpisode>)

/**
 * Result of a scan. [complete] `false`: a storage volume that held files is not mounted (review G6), the sync must not
 * remove what it didn't see.
 */
data class LocalScan<T>(val items: List<T>, val complete: Boolean = true)

/**
 * Finds audiobooks and podcast episodes on the device via MediaStore (concept 12.1): `m4b` directly in "Audiobooks" and folders full
 * of MP3 files in it. MediaStore suffices (no SAF) because the app has the audio permission for music anyway and
 * MediaStore lists files in "Audiobooks" as audio – but not as music, so they don't end up in the library.
 */
@Singleton
class LocalBookScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** Grant for the "Audiobooks" folder for the companion files. */
    val folders: LocalBookFolders = LocalBookFolders(context),
) {
    private val tagReader = LocalTagReader(context)
    private val volumes = VolumeWatch(context)

    /**
     * All books. For unchanged ones (according to [isUnchanged]) neither tags nor chapters are read.
     * Without permission this throws a `SecurityException`: otherwise MediaStore would return an empty list, and the
     * sync would delete all books including progress.
     */
    suspend fun scan(isUnchanged: suspend (key: String, fingerprint: Long) -> Boolean): LocalScan<LocalBook> =
        withContext(Dispatchers.IO) {
            if (!AudioPermission.isGranted(context)) throw SecurityException("${AudioPermission.name} missing")
            val trees = folders.trees()
            val rows = queryRows()
            val complete = volumes.complete(BOOKS_AREA, rows.mapNotNullTo(HashSet()) { it.volume })
            val books = LocalBookLayout.group(rows).map { book ->
                val added = book.files.minOf { it.dateAddedSec } * 1000
                // Companion files only with a folder grant and only for books in their own folder. If the folder is currently
                // unreadable, title, author and series would jump to tags and folder names without them until the next sync.
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
            LocalScan(books, complete)
        }

    /**
     * All podcasts from "Podcasts" (subfolder or album tag, see [LocalPodcastLayout]); errors and permission as with
     * [scan]. Unchanged episodes (according to [isUnchanged], with the name of their podcast) are not read.
     */
    suspend fun scanPodcasts(isUnchanged: suspend (podcast: String, key: String, fingerprint: Long) -> Boolean): LocalScan<LocalPodcast> =
        withContext(Dispatchers.IO) {
            if (!AudioPermission.isGranted(context)) throw SecurityException("${AudioPermission.name} missing")
            val untitled = context.getString(R.string.local_source_name)
            val rows = queryRows(LocalPodcastLayout.ROOT)
            val complete = volumes.complete(PODCASTS_AREA, rows.mapNotNullTo(HashSet()) { it.volume })
            val podcasts = LocalPodcastLayout.group(rows, untitled).map { podcast ->
                LocalPodcast(
                    podcast.name,
                    podcast.files.map { file ->
                        val key = LocalPodcastLayout.key(file)
                        val fingerprint = LocalPodcastLayout.fingerprint(file)
                        LocalEpisode(key, fingerprint, if (isUnchanged(podcast.name, key, fingerprint)) null else readEpisode(file))
                    },
                )
            }
            LocalScan(podcasts, complete)
        }

    private suspend fun readEpisode(file: AudioFileRow): LocalEpisodeDetails {
        val uri = contentUri(file.id)
        val tags = tagReader.readSpoken(uri) ?: SpokenTags()
        // Embedded chapters (ID3 CHAP, otherwise MP4) have the exact boundaries; the span extends to the end of the file.
        val chapters = tags.chapters.ifEmpty { (if (isMp4(file)) readMarks(uri) else emptyList()).toChapters(file.durationMs) }
        return LocalEpisodeDetails(
            title = file.title?.trim()?.takeIf { it.isNotEmpty() } ?: file.fileName.substringBeforeLast('.'),
            description = tags.description.orEmpty(),
            publishedAtMs = LocalPodcastLayout.publishedAt(tags.date, file.dateAddedSec * 1000),
            track = LocalBookTrack(uri.toString(), 0, file.durationMs, file.mimeType),
            chapters = chapters,
            hasCover = withRetriever(uri) { it.embeddedPicture } != null,
        )
    }

    /** Embedded cover (image file) of the file; `null` if there is none. */
    suspend fun cover(trackUri: String): ByteArray? = withContext(Dispatchers.IO) {
        withRetriever(Uri.parse(trackUri)) { it.embeddedPicture }
    }

    /** Cover from the book folder ([LocalBookDetails.coverUri]); `null` if it is not (or no longer) readable. */
    suspend fun folderCover(uri: String): ByteArray? = withContext(Dispatchers.IO) { folders.read(uri, MAX_IMAGE_BYTES) }

    /**
     * Reads a book. Precedence as with Audiobookshelf, the first one present in each case: `metadata.opf` before `desc.txt` or
     * `reader.txt` before the tags before the folder names.
     */
    private suspend fun read(book: LocalBookFiles, sidecars: BookSidecars): LocalBookDetails {
        var start = 0L
        val tracks = mutableListOf<LocalBookTrack>()
        val chapters = mutableListOf<LocalBookChapter>()
        // Subtitle and year are the same in every file; ID3 chapters only count for a single file.
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
        // One narrator per line or separated by commas.
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

    /** Text of a companion file: UTF-8, otherwise (Windows editors) Windows-1252. */
    private fun readText(file: SideFile): String? {
        val bytes = folders.read(file.uri, MAX_TEXT_BYTES) ?: return null
        val utf8 = String(bytes, Charsets.UTF_8)
        return (if ('\uFFFD' in utf8) String(bytes, charset("windows-1252")) else utf8).removePrefix("\uFEFF")
    }

    /**
     * Chapters of a file as a position in the book. Embedded chapters (m4b) take precedence; a single MP3 uses its
     * ID3 chapters ([id3]); otherwise, for a book made of several files, each file is a chapter, and a single file
     * without chapters has none.
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
        Log.w(TAG, "Chapters of $uri unreadable", e)
        emptyList()
    }

    private fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (e: RuntimeException) {
            // With broken files the retriever throws IllegalArgumentException/RuntimeException.
            Log.w(TAG, "Metadata of $uri unreadable", e)
            null
        } finally {
            retriever.release()
        }
    }

    private fun contentUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    @Suppress("DEPRECATION") // DATA (the path) is the only way to detect the directory on all supported versions.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.VOLUME_NAME)
        }
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns.toTypedArray(),
            "${MediaStore.Audio.Media.DATA} LIKE ?",
            arrayOf("%/$root/%"),
            null,
        ) ?: throw MediaStoreUnavailableException()
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
                            volume = c.string("volume_name"),
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
        const val BOOKS_AREA = "books"
        const val PODCASTS_AREA = "podcasts"
        const val MAX_TEXT_BYTES = 512L * 1024
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}
