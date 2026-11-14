package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.refresh.NewBook
import de.kleinabersmart.earloft.core.data.refresh.NewShow
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBook
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBookSummary
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdCategory
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdShow
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Eine abonnierte Sendung oder Reihe aus ARD Sounds: Ihre neuen Bücher kommen von selbst in die Bibliothek. */
data class ArdSubscription(val showId: String, val title: String)

/** Ergebnis eines Abgleichs der Bücher aus ARD Sounds: `ok`, wenn nichts fehlgeschlagen ist, und die neu in die Bibliothek gekommenen. */
data class ArdRefresh(val ok: Boolean, val newBooks: List<NewBook>)

/**
 * Hörbücher und Hörspiele aus ARD Sounds (`books_improvements.md` M9). Ein Buch kommt in die Bibliothek, sobald es abgespielt,
 * geladen oder hinzugefügt wird ([add]) oder zu einer abonnierten Sendung gehört ([subscribe]); es liegt wie die übrigen in `books` (`serverId = `[ARD_BOOK_SERVER_ID], `remoteId` = die
 * Kennung von [ArdSoundsBooks]), je Teil eine Datei mit ihrer Adresse und ein Kapitel. Der Abgleich ([refresh]) nimmt neue Teile
 * auf, lässt geladene stehen, auch wenn ARD Sounds sie nicht mehr führt, und entfernt nie ein Buch.
 */
@Singleton
class ArdBooks @Inject constructor(
    private val dao: BookDao,
    private val api: ArdSoundsBooks,
    private val settings: SpokenSettings,
    private val covers: BookCovers,
) {
    private val mutex = Mutex()

    /** Schalter Einstellungen → Bücher → Quellen. */
    val enabled: Flow<Boolean> get() = settings.ardSoundsBooks

    /** Die Bücher aus ARD Sounds in der Bibliothek: Kennung bei ARD Sounds → `bookId`. */
    val library: Flow<Map<String, Long>> =
        dao.observeBookRefsOf(ARD_BOOK_SERVER_ID).map { refs -> refs.associate { it.remoteId to it.bookId } }.distinctUntilChanged()

    /** Die abonnierten Sendungen. */
    val subscriptions: Flow<List<ArdSubscription>> get() = settings.ardSubscriptions

    suspend fun shows(category: ArdCategory): List<ArdShow> = api.shows(category)

    suspend fun showBooks(showId: String): List<ArdBookSummary> = api.showBooks(showId)

    suspend fun search(term: String): List<ArdBookSummary> = api.search(term)

    /** Das Buch für die Vorschau, ohne es zu speichern; `null`, wenn ARD Sounds es nicht (mehr) führt. */
    suspend fun preview(key: String): ArdBook? = api.book(key)

    /**
     * Nimmt das Buch [key] in die Bibliothek auf (oder aktualisiert es) und gibt seine `bookId` zurück. [known] spart den Abruf, wenn
     * die Vorschau es schon geladen hat; [addedAt] stammt aus einer Sicherung. `null`, wenn ARD Sounds es nicht (mehr) führt.
     * @throws IOException ohne Verbindung.
     */
    suspend fun add(key: String, known: ArdBook? = null, addedAt: Long? = null): Long? = mutex.withLock {
        dao.bookByRemote(ARD_BOOK_SERVER_ID, key)?.let { return@withLock it.bookId }
        val book = known ?: api.book(key) ?: return@withLock null
        store(book, existing = null, addedAt = addedAt)
    }

    /**
     * Abonniert die Sendung [showId]: Was von jetzt an dort erscheint, kommt in die Bibliothek (beim [refresh]); die Bücher, die es
     * schon gibt, nicht. @throws IOException ohne Verbindung.
     */
    suspend fun subscribe(showId: String, title: String) {
        settings.subscribeArd(showId, title, api.showBooks(showId).map { it.key })
    }

    suspend fun unsubscribe(showId: String) = settings.unsubscribeArd(showId)

    /** Entfernt das Buch aus der Bibliothek, samt Fortschritt und Lesezeichen; die Hörzeit bleibt in der Statistik. */
    suspend fun remove(bookId: Long) = mutex.withLock {
        val book = dao.book(bookId) ?: return@withLock
        if (book.serverId == ARD_BOOK_SERVER_ID) dao.deleteBook(bookId)
    }

    /**
     * Gleicht die Bücher aus ARD Sounds in der Bibliothek ab und holt die neuen der abonnierten Sendungen (nur mit eingeschaltetem
     * Schalter). `ok` ist `false`, wenn etwas nicht abrufbar war; das Übrige wird trotzdem abgeglichen.
     */
    suspend fun refresh(): ArdRefresh {
        if (!settings.ardSoundsBooks.first()) return ArdRefresh(ok = true, newBooks = emptyList())
        var ok = true
        for (existing in dao.booksOf(ARD_BOOK_SERVER_ID)) {
            try {
                val book = api.book(existing.remoteId)
                mutex.withLock {
                    // Inzwischen entfernt: nicht wieder anlegen.
                    val current = dao.book(existing.bookId) ?: return@withLock
                    if (book == null) markGone(current) else store(book, current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "${existing.title} aus ARD Sounds nicht abrufbar", e)
                ok = false
            }
        }
        val fresh = mutableListOf<NewBook>()
        for (subscription in settings.ardSubscriptions.first()) {
            try {
                fresh += takeNew(subscription)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "${subscription.title} aus ARD Sounds nicht abrufbar", e)
                ok = false
            }
        }
        return ArdRefresh(ok, fresh)
    }

    /**
     * Nimmt die Bücher einer abonnierten Sendung auf, die der Abgleich noch nicht kannte (höchstens [MAX_NEW_PER_CHECK], den Rest beim
     * nächsten Mal). Wer ein Buch aus der Bibliothek nimmt, bekommt es nicht wieder: Es gilt als bekannt. Ein Buch ohne Teile bei ARD
     * Sounds (angekündigt) bleibt unbekannt, bis es welche hat. @throws IOException, wenn etwas nicht abrufbar war.
     */
    private suspend fun takeNew(subscription: ArdSubscription): List<NewBook> {
        val seen = settings.ardSeen(subscription.showId)
        val unseen = api.showBooks(subscription.showId).filter { it.key !in seen }
        val taken = mutableListOf<NewBook>()
        val handled = mutableListOf<String>()
        try {
            for (summary in unseen) {
                if (taken.size >= MAX_NEW_PER_CHECK) break
                if (dao.bookByRemote(ARD_BOOK_SERVER_ID, summary.key) != null) {
                    handled += summary.key
                    continue
                }
                val bookId = add(summary.key) ?: continue
                taken += NewBook(bookId, summary.title, summary.author, NewShow(subscription.showId, subscription.title))
                handled += summary.key
            }
        } finally {
            settings.addArdSeen(subscription.showId, handled)
        }
        return taken
    }

    /** Ein Buch ohne veröffentlichte Teile: Es bleibt mit dem, was geladen ist, und gilt ab jetzt als abgelaufen. */
    private suspend fun markGone(existing: BookEntity) {
        val now = System.currentTimeMillis()
        if ((existing.availableUntil ?: Long.MAX_VALUE) > now) dao.updateBook(existing.copy(availableUntil = now))
    }

    private suspend fun store(book: ArdBook, existing: BookEntity?, addedAt: Long? = null): Long {
        val oldTracks = existing?.let { dao.tracks(it.bookId) }.orEmpty()
        val tracks = mutableListOf<BookTrackEntity>()
        var start = 0L
        book.parts.forEachIndexed { index, part ->
            tracks += BookTrackEntity(0, index, part.audioUrl, start, part.durationMs, part.mimeType)
            start += part.durationMs
        }
        // Ein geladener Teil, den ARD Sounds nicht mehr führt, bleibt samt der übrigen Dateien und Kapitel, wie er ist: Was offline
        // da ist, soll hörbar bleiben, und die Positionen im Buch verschöben sich sonst.
        val urls = tracks.mapTo(HashSet()) { it.remoteFileId }
        val keepOld = oldTracks.any { it.downloadState != TrackDownload.NONE && it.remoteFileId !in urls }
        val durationMs = if (keepOld) existing!!.durationMs else book.durationMs
        val entity = BookEntity(
            bookId = existing?.bookId ?: 0,
            serverId = ARD_BOOK_SERVER_ID,
            kind = BookKind.BOOK,
            remoteId = book.key,
            title = book.title,
            normTitle = book.title.normalizeForMerge(),
            author = book.author,
            narrator = book.narrator,
            description = book.description,
            durationMs = durationMs,
            addedAt = existing?.addedAt ?: addedAt ?: System.currentTimeMillis(),
            // Die Dateien kommen mit dem Buch; der Abgleich der Server soll sie nicht nachladen wollen.
            detailsLoaded = true,
            coverUrl = book.imageUrl,
            webUrl = book.webUrl,
            publisher = book.publisher.ifEmpty { null },
            genres = book.category?.let(::genreOf),
            availableUntil = book.availableUntil,
        )
        val bookId = if (existing == null) dao.upsertBook(entity) else existing.bookId.also { if (entity != existing) dao.updateBook(entity) }
        if (!keepOld) {
            val chapters = if (book.parts.size < 2) {
                emptyList()
            } else {
                tracks.mapIndexed { index, track ->
                    BookChapterEntity(bookId, index, book.parts[index].title, track.startMs, track.startMs + track.durationMs)
                }
            }
            val sameTracks = oldTracks.map { it.remoteFileId to it.durationMs } == tracks.map { it.remoteFileId to it.durationMs }
            val oldChapters = if (sameTracks) dao.chapters(bookId).map { it.title } else null
            if (!sameTracks || oldChapters != chapters.map { it.title }) dao.replaceDetails(bookId, tracks.map { it.copy(bookId = bookId) }, chapters)
        }
        if (existing != null && existing.coverUrl != book.imageUrl) covers.invalidate(bookId)
        return bookId
    }

    private companion object {
        const val TAG = "ArdBooks"
        const val MAX_NEW_PER_CHECK = 20

        /** Die Rubrik als Genre, wie ARD Sounds sie nennt. */
        fun genreOf(category: ArdCategory): String = when (category) {
            ArdCategory.AUDIOBOOK -> "Hörbuch"
            ArdCategory.RADIO_PLAY -> "Hörspiel"
        }
    }
}
