package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlin.math.abs

/** Remembered on this device (not in the backup): `bookId`s belong to this installation. */
private val Context.ardUploadsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ard_uploads")

/**
 * An uploaded book from ARD Sounds whose counterpart on the server has not been adopted yet: [bookId] (the ARD Sounds book)
 * in library [libraryId] of [serverId], [sentMs] the length of the uploaded parts. [before]: books that were already there
 * before the upload and would match by title or length; none of them is the upload.
 */
internal data class PendingUpload(
    val bookId: Long,
    val serverId: String,
    val libraryId: String,
    val sentMs: Long,
    val uploadedAt: Long,
    val before: Set<String>,
)

/**
 * After an upload ([ArdBookUploader]), carries progress, speed and effects, and bookmarks over to the book on the server as soon
 * as the server reports it. The pending adoption is stored ([remember]) and retried after every sync with the server ([settle]):
 * the server only scans an uploaded book afterwards, and otherwise waiting for it would survive neither "Cancel" nor process death.
 * It is discarded after [MAX_AGE_MS].
 */
@Singleton
class ArdUploadAdoption @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val progressSync: BookProgressSync,
    private val bookmarks: Bookmarks,
) {
    private val mutex = Mutex()

    /** Adopted since startup: `bookId` from ARD Sounds → `bookId` on the server. */
    private val adopted = ConcurrentHashMap<Long, Long>()

    /** Remembers the upload of [book] to [libraryId] on [serverId]; [onServer] are the server's books before the upload. */
    internal suspend fun remember(book: BookEntity, serverId: String, libraryId: String, sentMs: Long, onServer: List<BookEntity>) {
        val before = onServer
            .filter { it.libraryId == libraryId && (it.normTitle == book.normTitle || sameLength(it.durationMs, sentMs)) }
            .mapTo(HashSet()) { it.remoteId }
        val pending = PendingUpload(book.bookId, serverId, libraryId, sentMs, System.currentTimeMillis(), before)
        mutex.withLock {
            adopted.remove(book.bookId)
            write(read().filterNot { it.bookId == book.bookId } + pending)
        }
    }

    /**
     * Carries over the state of the pending uploads on [serverId] whose book the server now reports ([BookSyncer] after every
     * sync). Runs to completion even if the caller cancels.
     */
    internal suspend fun settle(serverId: String) = withContext(NonCancellable) {
        mutex.withLock {
            val all = read()
            if (all.none { it.serverId == serverId }) return@withLock
            val now = System.currentTimeMillis()
            val done = HashSet<Long>()
            for (pending in all.filter { it.serverId == serverId }) {
                val old = dao.book(pending.bookId)?.takeIf { isArdSoundsBook(it.serverId) }
                if (old == null || now - pending.uploadedAt > MAX_AGE_MS) {
                    if (old != null) Log.i(TAG, "${old.title}: Server hat den Upload nicht gemeldet, Übernahme verworfen")
                    done += pending.bookId
                    continue
                }
                val new = uploaded(old, pending) ?: continue
                adopt(old, new)
                adopted[old.bookId] = new.bookId
                done += pending.bookId
            }
            if (done.isNotEmpty()) write(all.filterNot { it.bookId in done })
        }
    }

    /** The book on the server that the state of [bookId] (from ARD Sounds) has been carried over to since startup. */
    internal fun adoptedTo(bookId: Long): Long? = adopted[bookId]

    /**
     * The uploaded book on the server. The server derives the title from the folder name (audio tags take precedence, characters
     * such as ":" are dropped). As a fallback, the only new book with a matching duration counts: otherwise it would be someone else's.
     */
    private suspend fun uploaded(old: BookEntity, pending: PendingUpload): BookEntity? {
        val fresh = dao.booksOf(pending.serverId).filter { it.libraryId == pending.libraryId && it.remoteId !in pending.before }
        return fresh.firstOrNull { it.normTitle == old.normTitle } ?: fresh.singleOrNull { sameLength(it.durationMs, pending.sentMs) }
    }

    /**
     * Carries progress, speed and effects, and bookmarks over from [old] to the book [new] on the server. The parts that are no
     * longer available and were never downloaded are missing there: whatever comes after them moves up accordingly.
     */
    private suspend fun adopt(old: BookEntity, new: BookEntity) {
        val missing = dao.tracks(old.bookId).filter { it.isUnavailable }
        val progress = dao.progress(old.bookId)
        if (progress != null) {
            val position = positionWithout(progress.positionMs, missing)
            progressSync.record(new.bookId, position, new.durationMs.takeIf { it > 0 } ?: progress.durationMs, progress.finished, force = true, hidden = progress.hidden)
            dao.setEffects(new.bookId, progress.speed, progress.skipSilence, progress.volumeBoostDb)
        }
        dao.bookmarksOf(listOf(old.bookId)).filter { !it.deleted }.forEach {
            bookmarks.add(new.bookId, positionWithout(it.positionMs, missing), it.title, it.note)
        }
        Log.i(TAG, "Stand von ${old.title} auf das Buch auf dem Server übernommen")
    }

    private suspend fun read(): List<PendingUpload> =
        context.ardUploadsDataStore.data.first()[KEY_PENDING].orEmpty().mapNotNull(::decode)

    private suspend fun write(pending: List<PendingUpload>) {
        context.ardUploadsDataStore.edit { it[KEY_PENDING] = pending.mapTo(HashSet(), ::encode) }
    }

    private companion object {
        const val TAG = "ArdUploadAdoption"
        val KEY_PENDING = stringSetPreferencesKey("pending")

        /** How long to wait for the book on the server (without a server watcher it only appears after a scan). */
        const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        fun encode(pending: PendingUpload): String = JSONObject()
            .put("bookId", pending.bookId)
            .put("serverId", pending.serverId)
            .put("libraryId", pending.libraryId)
            .put("sentMs", pending.sentMs)
            .put("uploadedAt", pending.uploadedAt)
            .put("before", JSONArray(pending.before.toList()))
            .toString()

        fun decode(entry: String): PendingUpload? = try {
            val json = JSONObject(entry)
            val before = json.getJSONArray("before")
            PendingUpload(
                json.getLong("bookId"), json.getString("serverId"), json.getString("libraryId"), json.getLong("sentMs"),
                json.getLong("uploadedAt"), (0 until before.length()).mapTo(HashSet(), before::getString),
            )
        } catch (_: JSONException) {
            null
        }
    }
}

private const val LENGTH_TOLERANCE_MS = 10_000L
private const val LENGTH_TOLERANCE_DIVISOR = 50

/** Whether two lengths can be the same recording: at most 10 s or 2 % difference. */
internal fun sameLength(a: Long, b: Long): Boolean = abs(a - b) <= maxOf(LENGTH_TOLERANCE_MS, b / LENGTH_TOLERANCE_DIVISOR)
