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
 * auf, lässt bei angefangenen Büchern jeden Teil an seinem Platz, auch wenn ARD Sounds ihn nicht mehr führt, und entfernt nie ein Buch.
 */
@Singleton
class ArdBooks @Inject constructor(
    private val dao: BookDao,
    private val api: ArdSoundsBooks,
    private val settings: SpokenSettings,
    private val covers: BookCovers,
) {
    private val mutex = Mutex()

    /** Ein Abgleich nach dem anderen: Laufen der Worker und ein schneller Abgleich zugleich, zählte [takeNew] ein Buch zweimal als neu. */
    private val refreshMutex = Mutex()

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
     * die Vorschau es schon geladen hat; [addedAt] und [saved] (die Teile auf dem alten Gerät, [decodeArdParts]) stammen aus einer
     * Sicherung. `null`, wenn ARD Sounds es nicht (mehr) führt. @throws IOException ohne Verbindung.
     */
    internal suspend fun add(key: String, known: ArdBook? = null, addedAt: Long? = null, saved: List<ArdBookPart>): Long? = mutex.withLock {
        dao.bookByRemote(ARD_BOOK_SERVER_ID, key)?.let { return@withLock it.bookId }
        val book = known ?: api.book(key) ?: return@withLock null
        // ARD Sounds hat die Teile anders zugeordnet: Das Buch heißt jetzt anders und ist womöglich schon da.
        dao.bookByRemote(ARD_BOOK_SERVER_ID, book.key)?.let { return@withLock it.bookId }
        store(book, existing = null, addedAt = addedAt, saved = saved)
    }

    /** Wie das andere [add], ohne Sicherung. */
    suspend fun add(key: String, known: ArdBook? = null): Long? = add(key, known, addedAt = null, saved = emptyList())

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
     * Schalter). `ok` ist `false`, wenn etwas nicht abrufbar war; das Übrige wird trotzdem abgeglichen. Ein abgelaufenes Buch,
     * nach dem ARD Sounds kürzlich gefragt wurde, bleibt ungefragt ([GONE_RECHECK_MS]).
     */
    suspend fun refresh(): ArdRefresh = refreshMutex.withLock {
        if (!settings.ardSoundsBooks.first()) return@withLock ArdRefresh(ok = true, newBooks = emptyList())
        var ok = true
        val checked = settings.ardGoneChecks()
        for (existing in dao.booksOf(ARD_BOOK_SERVER_ID)) {
            val now = System.currentTimeMillis()
            if (existing.isExpired(now) && checked[existing.remoteId]?.let { now - it < GONE_RECHECK_MS } == true) continue
            try {
                // Ordnet ARD Sounds die Titel anders zu, heißt das Buch jetzt anders; es behält dabei alles.
                val book = api.book(existing.remoteId) ?: api.movedKey(existing.remoteId)?.let { api.book(it) }
                mutex.withLock {
                    // Inzwischen entfernt: nicht wieder anlegen.
                    val current = dao.book(existing.bookId) ?: return@withLock
                    when {
                        book == null -> markGone(current)
                        // Unter dem neuen Schlüssel steht es schon in der Bibliothek: dieses bleibt, wie es ist.
                        book.key != current.remoteId && dao.bookByRemote(ARD_BOOK_SERVER_ID, book.key) != null ->
                            Log.i(TAG, "${current.title} gibt es bei ARD Sounds jetzt als ${book.key}, das schon in der Bibliothek ist")
                        else -> store(book, current)
                    }
                    // Abgelaufen, auch nach dem Abgleich: Eine Woche lang nicht wieder fragen.
                    val expired = dao.book(existing.bookId)?.isExpired(System.currentTimeMillis()) == true
                    if (expired) settings.setArdGoneCheck(existing.remoteId, now) else if (existing.remoteId in checked) settings.setArdGoneCheck(existing.remoteId, null)
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
        ArdRefresh(ok, fresh)
    }

    private fun BookEntity.isExpired(now: Long) = availableUntil?.let { it <= now } == true

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

    /**
     * Ein Buch ohne veröffentlichte Teile (auch nicht unter einem neuen Schlüssel): Es bleibt mit allen Teilen, die nicht geladenen gelten ab jetzt als nicht mehr abrufbar
     * und das Buch als abgelaufen.
     */
    private suspend fun markGone(existing: BookEntity) {
        val now = System.currentTimeMillis()
        val parts = mergeArdParts(knownParts(existing.bookId), offered = emptyList(), now)
        val until = ardAvailableUntil(parts, whenAllLoaded = existing.availableUntil?.coerceAtMost(now) ?: now)
        if (until != existing.availableUntil) dao.updateBook(existing.copy(availableUntil = until))
        saveParts(existing.bookId, parts)
    }

    /**
     * Speichert das Buch. Ein angefangenes Buch (Fortschritt, Lesezeichen oder Downloads) behält seine Teile an ihrem Platz und
     * bekommt neue hinten dazu ([mergeArdParts]): Teile laufen bei ARD Sounds einzeln ab, und sonst verschöben sich Position,
     * Lesezeichen, Kapitel und die Zuordnung der geladenen Dateien. Ebenso eins aus einer Sicherung mit den Teilen des alten Geräts
     * ([saved]). Ein noch nicht angefangenes nimmt die Teile, wie sie sind.
     */
    private suspend fun store(book: ArdBook, existing: BookEntity?, addedAt: Long? = null, saved: List<ArdBookPart> = emptyList()): Long {
        val known = existing?.let { knownParts(it.bookId) }.orEmpty()
        val parts = when {
            existing != null && known.isNotEmpty() && isStarted(existing.bookId, known) -> mergeArdParts(known, book.parts, System.currentTimeMillis())
            existing == null && saved.isNotEmpty() -> mergeArdParts(saved, book.parts, System.currentTimeMillis())
            else -> freshArdParts(book.parts)
        }
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
            durationMs = parts.sumOf { it.track.durationMs },
            addedAt = existing?.addedAt ?: addedAt ?: System.currentTimeMillis(),
            // Die Dateien kommen mit dem Buch; der Abgleich der Server soll sie nicht nachladen wollen.
            detailsLoaded = true,
            coverUrl = book.imageUrl,
            webUrl = book.webUrl,
            publisher = book.publisher.ifEmpty { null },
            genres = book.category?.let(::genreOf),
            availableUntil = ardAvailableUntil(parts, whenAllLoaded = book.availableUntil),
        )
        val bookId = if (existing == null) dao.upsertBook(entity) else existing.bookId.also { if (entity != existing) dao.updateBook(entity) }
        saveParts(bookId, parts)
        if (existing != null && existing.coverUrl != book.imageUrl) covers.invalidate(bookId)
        return bookId
    }

    /** Die Teile des Buchs in der Bibliothek, mit den Kapitelnamen (bei nur einem Teil gibt es kein Kapitel). */
    private suspend fun knownParts(bookId: Long): List<ArdBookPart> {
        val titles = dao.chapters(bookId).associate { it.chapterIndex to it.title }
        return dao.tracks(bookId).sortedBy { it.trackIndex }.map { ArdBookPart(it, titles[it.trackIndex].orEmpty(), it.unavailableSince) }
    }

    private suspend fun isStarted(bookId: Long, known: List<ArdBookPart>): Boolean {
        if (known.any { it.track.downloadState in STARTED_DOWNLOADS }) return true
        val progress = dao.progress(bookId)
        if (progress != null && (progress.finished || progress.positionMs > 0)) return true
        return dao.bookmarksOf(listOf(bookId)).any { !it.deleted }
    }

    /** Schreibt Dateien und Kapitel (je Teil eines, ab zwei Teilen), wenn sich etwas geändert hat. */
    private suspend fun saveParts(bookId: Long, parts: List<ArdBookPart>) {
        val tracks = parts.map { it.track.copy(bookId = bookId) }
        val chapters = if (parts.size < 2) {
            emptyList()
        } else {
            parts.mapIndexed { index, part ->
                BookChapterEntity(bookId, index, part.title, part.track.startMs, part.track.startMs + part.track.durationMs)
            }
        }
        // Der Download-Stand zählt nicht: Den behält [BookDao.replaceDetails] für gleich gebliebene Dateien ohnehin.
        fun layout(list: List<BookTrackEntity>) =
            list.sortedBy { it.trackIndex }.map { listOf(it.trackIndex, it.remoteFileId, it.startMs, it.durationMs, it.mimeType, it.unavailableSince) }
        if (layout(dao.tracks(bookId)) != layout(tracks) || dao.chapters(bookId).sortedBy { it.chapterIndex } != chapters) {
            dao.replaceDetails(bookId, tracks, chapters)
        }
    }

    private companion object {
        const val TAG = "ArdBooks"
        const val MAX_NEW_PER_CHECK = 20

        /** So lange wird ein abgelaufenes Buch nicht wieder bei ARD Sounds abgefragt (es kommt selten wieder). */
        const val GONE_RECHECK_MS = 7 * 24 * 60 * 60 * 1000L

        /** Downloads, die ein Buch als angefangen gelten lassen. */
        val STARTED_DOWNLOADS = setOf(TrackDownload.DONE, TrackDownload.QUEUED, TrackDownload.RUNNING)

        /** Die Rubrik als Genre, wie ARD Sounds sie nennt. */
        fun genreOf(category: ArdCategory): String = when (category) {
            ArdCategory.AUDIOBOOK -> "Hörbuch"
            ArdCategory.RADIO_PLAY -> "Hörspiel"
        }
    }
}
