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

/** Merker dieses Geräts (nicht in der Sicherung): `bookId`s gehören zu dieser Installation. */
private val Context.ardUploadsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ard_uploads")

/**
 * Ein hochgeladenes Buch aus ARD Sounds, dessen Gegenstück auf dem Server noch nicht übernommen ist: [bookId] (das Buch aus ARD Sounds)
 * in der Bibliothek [libraryId] von [serverId], [sentMs] Länge der hochgeladenen Teile. [before]: Bücher, die schon vor dem Upload dort
 * standen und nach Titel oder Länge passen würden; keins davon ist der Upload.
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
 * Übernimmt nach einem Upload ([ArdBookUploader]) Fortschritt, Tempo und Effekte und Lesezeichen auf das Buch auf dem Server, sobald
 * der es meldet. Die offene Übernahme ist gespeichert ([remember]) und wird nach jedem Abgleich des Servers versucht ([settle]): Der
 * Server scannt ein hochgeladenes Buch erst danach, und das Warten darauf überlebt sonst weder „Abbrechen“ noch das Ende des Prozesses.
 * Nach [MAX_AGE_MS] wird sie verworfen.
 */
@Singleton
class ArdUploadAdoption @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val progressSync: BookProgressSync,
    private val bookmarks: Bookmarks,
) {
    private val mutex = Mutex()

    /** Seit dem Start übernommen: `bookId` aus ARD Sounds → `bookId` auf dem Server. */
    private val adopted = ConcurrentHashMap<Long, Long>()

    /** Merkt den Upload von [book] nach [libraryId] auf [serverId]; [onServer] sind die Bücher des Servers vor dem Upload. */
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
     * Übernimmt den Stand der offenen Uploads auf [serverId], deren Buch der Server inzwischen meldet ([BookSyncer] nach jedem
     * Abgleich). Läuft zu Ende, auch wenn der Aufrufer abbricht.
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

    /** Das Buch auf dem Server, auf das der Stand von [bookId] (aus ARD Sounds) seit dem Start übernommen wurde. */
    internal fun adoptedTo(bookId: Long): Long? = adopted[bookId]

    /**
     * Das hochgeladene Buch auf dem Server. Der Server macht aus dem Ordnernamen den Titel (Audio-Tags gehen vor, Zeichen wie „:“ fallen
     * weg). Notfalls gilt das einzige neue Buch mit passender Dauer: Sonst wäre es das einer anderen Person.
     */
    private suspend fun uploaded(old: BookEntity, pending: PendingUpload): BookEntity? {
        val fresh = dao.booksOf(pending.serverId).filter { it.libraryId == pending.libraryId && it.remoteId !in pending.before }
        return fresh.firstOrNull { it.normTitle == old.normTitle } ?: fresh.singleOrNull { sameLength(it.durationMs, pending.sentMs) }
    }

    /**
     * Übernimmt Fortschritt, Tempo und Effekte und Lesezeichen von [old] auf das Buch [new] auf dem Server. Die nicht mehr abrufbaren
     * und nie geladenen Teile fehlen dort: Was danach kommt, rückt um sie vor.
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

        /** So lange wird auf das Buch auf dem Server gewartet (ohne Server-Watcher erscheint es erst nach einem Scan). */
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

/** Ob zwei Längen dieselbe Aufnahme sein können: höchstens 10 s oder 2 % Abweichung. */
internal fun sameLength(a: Long, b: Long): Boolean = abs(a - b) <= maxOf(LENGTH_TOLERANCE_MS, b / LENGTH_TOLERANCE_DIVISOR)
