package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.decodeArdParts
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.entity.UserArdBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeDownloadEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeStarEntity
import de.kleinabersmart.earloft.core.database.entity.UserCollectionFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält `user.db` ([de.kleinabersmart.earloft.core.database.UserDatabase]) auf dem Stand der Hauptdatenbank (Review K9).
 *
 * - **Zurückspielen:** Beim ersten Start mit dieser Installation (Merker in `noBackupFilesDir`, der nie gesichert wird)
 *   kommen die gesicherten Zeilen in die Hauptdatenbank, sofern sie dort fehlen. Nach einem Gerätewechsel ist das der
 *   einzige Weg zurück; bei einem Update ist `user.db` leer, und es passiert nichts.
 * - **Fortschritt** hängt an Büchern, die es erst nach Scan bzw. Abgleich wieder gibt. Er wird deshalb bei jedem Start
 *   und danach immer dann angewendet, wenn neue Bücher auftauchen, und zwar nur, wenn er neuer ist als der vorhandene.
 *   Solange sein Buch fehlt, bleibt er in `user.db` stehen; mit seinem Abo oder Server fällt er weg.
 * - **Lesezeichen** ebenso; sie kommen dazu, wenn ihr Buch an derselben Stelle noch keins hat.
 * - **Gemerkte Folgen** ebenso; eine schon gemerkte behält ihren Zeitpunkt.
 * - **Bücher aus ARD Sounds** gibt kein Scan und kein Server zurück: Sie holt [restoreArdBooks] von ARD Sounds, mit dem alten Zeitpunkt
 *   des Hinzufügens; ohne Verbindung beim nächsten Start. Danach gelten ihr Fortschritt und ihre Lesezeichen wie bei den übrigen.
 * - **Musik** ([MusicBackup]) ebenso, sobald ihre Songs, Alben und Playlists wieder da sind: lokale Playlists (werden beim
 *   Zurückspielen leer angelegt), Favoriten von Songs ohne Server, Hörverlauf und die Auswahl für die Uhr.
 * - **Downloads der Folgen** ebenso, nach dem Fortschritt: Was auf dem alten Gerät geladen war, kommt wieder, solange es
 *   nicht gehört ist; mehr nicht – bei einem zurückgespielten Abo lässt die Automatik nach dem ersten Abgleich alle übrigen
 *   Folgen aus, erst danach erscheinende lädt sie wieder. Sie wendet vor jeder Entscheidung an, was schon passt
 *   ([applyWaiting]); sonst lüde sie nach dem Abgleich eines zurückgespielten Abos, bevor der Fortschritt da ist.
 * - **Spiegeln:** Danach überträgt jede Änderung der betroffenen Tabellen nur die Unterschiede, höchstens einmal je
 *   [MIRROR_INTERVAL_MS]. Das Backup läuft ohnehin selten (Cloud etwa täglich); so kann kein Schreibweg vergessen werden.
 *   Gelesen werden nur die Teile, deren Tabellen sich geändert haben (beim Hören eines Buchs alle 15 s nur Fortschritt und
 *   Statistik statt aller Nutzerdaten); alle [FULL_READ_INTERVAL_MS] wieder alles, für Änderungen über nicht beobachtete
 *   Tabellen (etwa umbenannte Alben in den Schlüsseln).
 */
@Singleton
class UserDataBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: EarLoftDatabase,
    private val userDao: UserDataDao,
    private val sourceDao: SourceDao,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
    private val statsDao: StatsDao,
    private val music: MusicBackup,
    private val restoreNotice: RestoreNotice,
    /** Erst gebraucht, wenn wirklich etwas zurückgespielt wurde. */
    private val registry: Lazy<DataSourceRegistry>,
    private val ardBooks: Lazy<ArdBooks>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Fertig, sobald das Zurückspielen beim Start gelaufen ist, auch wenn es fehlschlug. */
    private val restoreFinished = CompletableDeferred<Unit>()

    /** Ob es geklappt hat; sonst steht in `user.db` noch eine Sicherung, die das Spiegeln nicht überschreiben darf. */
    @Volatile
    private var restoreSucceeded = false

    /** Fertig, sobald nach dem Start einmal angewendet wurde, was schon passt (auch wenn das Zurückspielen fehlschlug). */
    private val firstApplied = CompletableDeferred<Unit>()

    /** Gesichertes, das noch auf sein Buch, seinen Song usw. wartet, samt dem Stand, aus dem es stammt; ändert sich unter [mutex]. */
    @Volatile
    private var waiting: Waiting? = null

    private class Waiting(val saved: UserDataSnapshot, val pending: Pending)

    private val _downloadsQueued = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Gesicherte Downloads von Folgen stehen wieder an; `EpisodeDownloads` startet dann seinen Auftrag. */
    val downloadsQueued: Flow<Unit> = _downloadsQueued

    /** Spiegeln und Anwenden lesen und schreiben dieselben Zeilen. */
    private val mutex = Mutex()

    /** Der zuletzt nach `user.db` geschriebene Stand; spart das erneute Lesen von `user.db` bei jedem Abgleich. */
    private var mirrored: UserDataSnapshot? = null

    /** Wann zuletzt alle Teile gelesen wurden (`elapsedRealtime`). */
    private var fullReadAt = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                try {
                    restoreOnce()
                    restoreSucceeded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ohne Merker: Beim nächsten Start wird es erneut versucht. Gespiegelt wird trotzdem nicht, damit eine
                    // noch nicht zurückgespielte Sicherung nicht vom leeren Stand überschrieben wird.
                    Log.e(TAG, "Nutzerdaten konnten nicht zurückgespielt werden", e)
                    return@launch
                } finally {
                    restoreFinished.complete(Unit)
                }
                val saved = readUser()
                mutex.withLock { applyAndWait(saved, pendingOf(saved)) }
            } finally {
                firstApplied.complete(Unit)
            }
            launch { restoreArdBooks() }
            launch { applyWhenLibraryChanges() }
            // Was sich geändert hat, sammelt sich hier, auch während gerade gespiegelt wird oder gewartet (`conflate`).
            val changed = ConcurrentHashMap.newKeySet<String>()
            database.invalidationTracker.createFlow(*MIRRORED_TABLES)
                .onEach { changed += it }
                .conflate()
                .collect {
                    val tables = changed.toSet().also { changed.removeAll(it) }
                    try {
                        mutex.withLock { mirror(tables) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Nutzerdaten konnten nicht gesichert werden", e)
                        mirrored = null
                    }
                    delay(MIRROR_INTERVAL_MS)
                }
        }
    }

    /**
     * Wartet, bis gesicherte Server und Abos wieder in der Hauptdatenbank stehen (nur beim ersten Start einer Installation
     * dauert das merklich). Der Einrichtungsassistent entscheidet erst danach, ob es schon Inhalt gibt.
     */
    suspend fun awaitRestore() = restoreFinished.await()

    /**
     * Wendet an, was von der Sicherung jetzt passt. Die Download-Automatik ruft das vor jeder Entscheidung: Gesicherter
     * Fortschritt und Downloads sollen gelten, bevor sie die neuesten ungehörten Folgen lädt – sonst lüde sie nach dem
     * Abgleich eines zurückgespielten Abos gehörte Folgen und solche, deren Download auf dem alten Gerät entfernt wurde.
     */
    suspend fun applyWaiting() {
        // Ohne [start] (etwa in Tests) gibt es nichts anzuwenden.
        if (!started.get()) return
        firstApplied.await()
        if (waiting == null) return
        mutex.withLock { waiting?.let { applyAndWait(it.saved, it.pending) } }
    }

    /**
     * Alles aus [saved] als noch anzuwenden; dazu die zurückgespielten Abos, die noch nie abgeglichen wurden und noch keine
     * Folgen haben: Nach ihrem ersten Abgleich lädt die Automatik dort nur, was auf dem alten Gerät geladen war.
     */
    private suspend fun pendingOf(saved: UserDataSnapshot): Pending = Pending.of(saved).copy(
        restoredFeeds = saved.podcasts.keys.filter { feedId ->
            podcastDao.feed(feedId)?.lastRefreshAt == 0L && podcastDao.episodeCount(feedId) == 0
        },
    )

    /** Unter [mutex]: wendet an und merkt sich den Rest für später. */
    private suspend fun applyAndWait(saved: UserDataSnapshot, pending: Pending) {
        val rest = applySaved(saved, pending)
        waiting = if (rest.isEmpty) null else Waiting(saved, rest)
    }

    /**
     * Der Stand für die Komplett-Sicherung (`FullBackup`): `user.db` frisch gespiegelt, samt dem, was noch auf sein Buch,
     * seinen Song usw. wartet.
     */
    suspend fun exportSnapshot(): UserDataSnapshot {
        restoreFinished.await()
        return mutex.withLock {
            if (restoreSucceeded) {
                mirror()
                readUser()
            } else {
                readMain(null, emptySet()).plusMissing(readUser())
            }
        }
    }

    /**
     * Spielt die Nutzerdaten einer Komplett-Sicherung ein. Wie beim Gerätewechsel wird nur ergänzt: Vorhandenes bleibt,
     * Fortschritt und Favoriten gelten, wenn sie neuer sind. Server und Abos, die es hier unter anderer Kennung schon gibt
     * (gleiche Adresse), werden nicht doppelt angelegt. Was noch kein Buch, keinen Song usw. hat, kommt nach `user.db` und
     * folgt wie nach einem Gerätewechsel, auch über einen Neustart hinweg. Neue Server erscheinen danach mit „Passwort neu
     * eingeben“.
     */
    suspend fun import(saved: UserDataSnapshot): BackupCounts {
        // Erst nach dem ersten Anwenden beim Start: Das überschriebe sonst mit seinem älteren Stand, was hier wartet.
        if (started.get()) firstApplied.await() else restoreFinished.await()
        val result = mutex.withLock {
            val adjusted = withLocalIds(saved)
            val musicIds = sourceDao.getServers().map { it.sourceId }.toSet()
            val spokenIds = bookDao.servers().map { it.serverId }.toSet()
            val result = BackupCounts(
                musicServers = adjusted.servers.keys.count { it !in musicIds },
                audiobookServers = adjusted.spokenServers.keys.count { it !in spokenIds },
                podcasts = adjusted.podcasts.keys.count { podcastDao.feed(it) == null },
                playlists = music.missingPlaylists(adjusted.playlists.keys),
            )
            restore(adjusted)
            val current = readUser()
            val merged = current.plusMissing(adjusted)
            val changes = merged.changesSince(current)
            if (!changes.isEmpty) userDao.apply(changes)
            // Der gemerkte Stand stimmt nicht mehr mit `user.db` überein; beim nächsten Spiegeln neu lesen.
            mirrored = null
            applyAndWait(merged, pendingOf(merged))
            result
        }
        scope.launch { restoreArdBooks() }
        // Den Hinweis auf fehlende Passwörter meldet `FullBackup` mit seinem Ergebnis.
        if (result.musicServers > 0) registry.get().reload()
        Log.i(TAG, "Sicherung eingespielt: $result")
        return result
    }

    /**
     * Nimmt aus [saved] Server und Abos heraus, die es hier unter anderer Kennung gibt, und hängt ihren Fortschritt, ihre
     * Lesezeichen und ihre Downloads an die hiesige Kennung.
     */
    private suspend fun withLocalIds(saved: UserDataSnapshot): UserDataSnapshot {
        val music = sourceDao.getServers()
        val servers = saved.servers.filterValues { server ->
            music.none { it.sourceId != server.sourceId && it.type == server.type && sameUrl(it.baseUrl, server.baseUrl) && it.username == server.username }
        }
        val spoken = bookDao.servers()
        val spokenIds = saved.spokenServers.values.associate { server ->
            // Über die Adresse: `libraryId` ist seit den mehreren Bibliotheken nur die erste gezeigte und wechselt mit Auswahl und
            // Reihenfolge in Audiobookshelf. Bei mehreren Einträgen derselben Adresse (alte Sicherungen) zuerst der mit gleicher.
            val local = spoken.firstOrNull { it.serverId == server.serverId }
                ?: spoken.filter { sameUrl(it.baseUrl, server.baseUrl) }.let { same -> same.firstOrNull { it.libraryId == server.libraryId } ?: same.firstOrNull() }
            server.serverId to (local?.serverId ?: server.serverId)
        }
        val feedIds = saved.podcasts.values.associate { podcast ->
            podcast.feedId to ((podcastDao.feed(podcast.feedId) ?: podcastDao.feedByUrl(podcast.url))?.feedId ?: podcast.feedId)
        }
        val ids = spokenIds + feedIds
        return saved.copy(
            servers = servers,
            spokenServers = saved.spokenServers.filterKeys { spokenIds[it] == it },
            podcasts = saved.podcasts.filterKeys { feedIds[it] == it },
            progress = saved.progress.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .groupBy { BookKey(it.serverId, it.remoteId) }
                .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } },
            episodeDownloads = saved.episodeDownloads.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .associateBy { BookKey(it.serverId, it.remoteId) },
            bookmarks = saved.bookmarks.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .groupBy { it.key }
                .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } },
            episodeStars = saved.episodeStars.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .associateBy { BookKey(it.serverId, it.remoteId) },
        )
    }

    private fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

    /** Die Liste der Server ohne Passwort entsteht beim Laden; die Einstellungen sollen „Passwort neu eingeben“ zeigen. */
    private suspend fun serversRestored(music: Boolean, audiobooks: Boolean) {
        if (music) registry.get().reload()
        if (music || audiobooks) restoreNotice.serversRestored()
    }

    private suspend fun restoreOnce() {
        val marker = File(context.noBackupFilesDir, RESTORED_MARKER)
        if (withContext(Dispatchers.IO) { marker.exists() }) return
        val saved = readUser()
        if (!saved.isEmpty) {
            restore(saved)
            serversRestored(saved.servers.isNotEmpty(), saved.spokenServers.isNotEmpty())
            Log.i(
                TAG,
                "Nutzerdaten zurückgespielt: ${saved.servers.size} Musik-Server, ${saved.spokenServers.size} ABS-Server, " +
                    "${saved.podcasts.size} Podcasts, ${saved.listeningStats.size} Statistik-Einträge, " +
                    "${saved.progress.size} Fortschritte, ${saved.bookmarks.size} Lesezeichen, ${saved.playlists.size} Playlists, ${saved.favorites.size} Favoriten, " +
                    "${saved.playEvents.size} Einträge im Hörverlauf, ${saved.wearSelections.size} Auswahlen für die Uhr, " +
                    "${saved.episodeDownloads.size} Downloads von Folgen, ${saved.episodeStars.size} gemerkte Folgen " +
                    "(folgen mit ihren Büchern und Songs)",
            )
        }
        withContext(Dispatchers.IO) { marker.createNewFile() }
    }

    /** Legt an, was in der Hauptdatenbank fehlt; Vorhandenes bleibt unverändert. */
    internal suspend fun restore(saved: UserDataSnapshot) {
        database.withTransaction {
            saved.servers.values.forEach { sourceDao.insertIfAbsent(it.toMain()) }
            saved.spokenServers.values.forEach { if (bookDao.server(it.serverId) == null) bookDao.upsertServer(it.toMain()) }
            saved.podcasts.values.forEach {
                if (podcastDao.feed(it.feedId) == null && podcastDao.feedByUrl(it.url) == null) podcastDao.upsertFeed(it.toMain())
            }
            statsDao.insertAllIfAbsent(saved.listeningStats.values.map { it.toMain() })
            statsDao.insertDaysIfAbsent(saved.listeningDays.values.map { it.toMain() })
            music.restorePlaylists(saved.playlists.values)
        }
    }

    /**
     * Überträgt gesicherten Fortschritt in die Hauptdatenbank, wenn es sein Buch dort gibt und er neuer ist als der
     * vorhandene. Liefert den Rest, dessen Buch (noch) fehlt.
     */
    internal suspend fun applyProgress(saved: Collection<UserBookProgressEntity>): List<UserBookProgressEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = mutableListOf<UserBookProgressEntity>()
        var applied = 0
        database.withTransaction {
            saved.groupBy { it.serverId }.forEach { (serverId, rows) ->
                val bookIds = rows.chunked(MAX_ARGS)
                    .flatMap { chunk -> bookDao.bookRefs(serverId, chunk.map { it.remoteId }) }
                    .associate { it.remoteId to it.bookId }
                val current = bookIds.values.chunked(MAX_ARGS).flatMap { bookDao.progressOf(it) }.associateBy { it.bookId }
                rows.forEach { row ->
                    val bookId = bookIds[row.remoteId]
                    when {
                        bookId == null -> unresolved += row
                        (current[bookId]?.updatedAt ?: Long.MIN_VALUE) < row.updatedAt -> {
                            bookDao.upsertProgress(row.toMain(bookId))
                            applied++
                        }
                    }
                }
            }
        }
        if (applied > 0) Log.i(TAG, "Fortschritt zurückgespielt: $applied, noch ohne Buch: ${unresolved.size}")
        return unresolved
    }

    /**
     * Überträgt gesicherte Lesezeichen in die Hauptdatenbank, wenn es ihr Buch dort gibt und an derselben Stelle noch keins
     * (auch kein gelöschtes, dessen Meldung aussteht). Hat das vorhandene keine Notiz, bekommt es die gesicherte:
     * Audiobookshelf kennt keine Notizen, der Abgleich bringt seine Lesezeichen ohne sie zurück. Liefert den Rest, dessen
     * Buch (noch) fehlt.
     */
    internal suspend fun applyBookmarks(saved: Collection<UserBookmarkEntity>): List<UserBookmarkEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = mutableListOf<UserBookmarkEntity>()
        var applied = 0
        database.withTransaction {
            val abs = bookDao.servers().map { it.serverId }.toSet()
            saved.groupBy { it.serverId }.forEach { (serverId, rows) ->
                val bookIds = rows.map { it.remoteId }.distinct().chunked(MAX_ARGS)
                    .flatMap { chunk -> bookDao.bookRefs(serverId, chunk) }
                    .associate { it.remoteId to it.bookId }
                val current = bookIds.values.chunked(MAX_ARGS).flatMap { bookDao.bookmarksOf(it) }
                    .associateBy { it.bookId to it.positionMs }
                rows.forEach { row ->
                    val bookId = bookIds[row.remoteId]
                    val existing = bookId?.let { current[it to row.positionMs] }
                    when {
                        bookId == null -> unresolved += row
                        existing == null -> {
                            // Von Audiobookshelf-Büchern ist ohne Notiz nur gesichert, was der Server noch nicht (so) hat:
                            // wieder zu melden. Mit Notiz kann es auch eins sein, das er schon kennt.
                            val dirty = serverId in abs && (row.remoteTime == null || row.note.isEmpty())
                            bookDao.insertBookmark(row.toMain(bookId, dirty))
                            applied++
                        }
                        !existing.deleted && existing.note.isEmpty() && row.note.isNotEmpty() -> {
                            bookDao.updateBookmark(existing.copy(note = row.note))
                            applied++
                        }
                    }
                }
            }
        }
        if (applied > 0) Log.i(TAG, "Lesezeichen zurückgespielt: $applied, noch ohne Buch: ${unresolved.size}")
        return unresolved
    }

    /**
     * Überträgt den gesicherten Download-Zustand auf Folgen, die es hier gibt und die hier noch nie geladen wurden:
     * entfernte und fehlgeschlagene lässt die Automatik aus, geladene kommen wieder, solange sie nicht zu Ende gehört sind.
     * Liefert den Rest, dessen Folge (noch) fehlt.
     */
    internal suspend fun applyEpisodeDownloads(saved: Collection<UserEpisodeDownloadEntity>): List<UserEpisodeDownloadEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = mutableListOf<UserEpisodeDownloadEntity>()
        var excluded = 0
        var queued = 0
        database.withTransaction {
            saved.groupBy { it.serverId }.forEach { (serverId, rows) ->
                val bookIds = rows.chunked(MAX_ARGS)
                    .flatMap { chunk -> bookDao.bookRefs(serverId, chunk.map { it.remoteId }) }
                    .associate { it.remoteId to it.bookId }
                val finished = bookIds.values.chunked(MAX_ARGS).flatMap { bookDao.progressOf(it) }.filter { it.finished }.map { it.bookId }.toSet()
                val found = rows.mapNotNull { row ->
                    val bookId = bookIds[row.remoteId]
                    if (bookId == null) unresolved += row
                    bookId?.let { row to it }
                }
                val (wanted, notWanted) = found.partition { (row, _) -> row.wanted }
                notWanted.map { it.second }.chunked(MAX_ARGS).forEach { excluded += podcastDao.excludeFromAutomatic(it) }
                wanted.map { it.second }.filter { it !in finished }.chunked(MAX_ARGS).forEach { queued += podcastDao.queueNeverDownloaded(it) }
            }
        }
        if (excluded > 0 || queued > 0) Log.i(TAG, "Downloads zurückgespielt: $queued geladen, $excluded ausgelassen, noch ohne Folge: ${unresolved.size}")
        if (queued > 0) _downloadsQueued.tryEmit(Unit)
        return unresolved
    }

    /** Merkt die gesicherten Folgen, die es hier gibt; liefert den Rest, dessen Folge (noch) fehlt. */
    internal suspend fun applyEpisodeStars(saved: Collection<UserEpisodeStarEntity>): List<UserEpisodeStarEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = database.withTransaction {
            saved.filter { podcastDao.restoreStar(it.serverId, it.remoteId, it.starredAt) == 0 }
        }
        if (unresolved.size < saved.size) Log.i(TAG, "Gemerkte Folgen zurückgespielt: ${saved.size - unresolved.size}, noch ohne Folge: ${unresolved.size}")
        return unresolved
    }

    /** Liefert die gesicherten Bücher aus ARD Sounds, die es hier (noch) nicht gibt; geholt werden sie von [restoreArdBooks]. */
    internal suspend fun applyArdBooks(saved: Collection<UserArdBookEntity>): List<UserArdBookEntity> {
        if (saved.isEmpty()) return emptyList()
        val here = bookDao.keyedBooksOf(ARD_BOOK_SERVER_ID).mapTo(HashSet()) { it.remoteId }
        return saved.filter { it.remoteId !in here }
    }

    /** Wendet von [saved] an, was davon in [pending] steht; liefert, was (noch) kein Buch, keinen Song usw. hat. */
    internal suspend fun applySaved(saved: UserDataSnapshot, pending: Pending): Pending {
        val progress = applyProgress(pending.progress)
        val bookmarks = applyBookmarks(pending.bookmarks)
        val episodeStars = applyEpisodeStars(pending.episodeStars)
        val ardBooks = applyArdBooks(pending.ardBooks)
        // Nach dem Fortschritt: Gehörte Folgen kommen nicht wieder.
        val episodeDownloads = applyEpisodeDownloads(pending.episodeDownloads)
        // Danach: Mehr als auf dem alten Gerät geladen war, lädt die Automatik für zurückgespielte Abos nicht.
        val restoredFeeds = pending.restoredFeeds.filter { feedId ->
            val arrived = podcastDao.episodeCount(feedId) > 0
            if (arrived) podcastDao.excludeAllFromAutomatic(feedId)
            !arrived
        }
        val rest = database.withTransaction {
            Pending(
                progress = progress,
                bookmarks = bookmarks,
                episodeDownloads = episodeDownloads,
                episodeStars = episodeStars,
                ardBooks = ardBooks,
                restoredFeeds = restoredFeeds,
                playlists = music.applyPlaylists(saved, pending.playlists),
                favorites = music.applyFavorites(pending.favorites),
                collectionFavorites = music.applyCollectionFavorites(pending.collectionFavorites),
                playEvents = music.applyPlayEvents(pending.playEvents),
                wearSelections = music.applyWearSelections(pending.wearSelections),
            )
        }
        val found = listOf(
            "Favoriten" to pending.favorites.size - rest.favorites.size,
            "Alben/Künstler" to pending.collectionFavorites.size - rest.collectionFavorites.size,
            "Hörverlauf" to pending.playEvents.size - rest.playEvents.size,
            "Uhr" to pending.wearSelections.size - rest.wearSelections.size,
        ).filter { it.second > 0 }
        if (found.isNotEmpty() || rest.playlists != pending.playlists) {
            Log.i(
                TAG,
                "Musik zurückgespielt: ${found.joinToString { "${it.first} ${it.second}" }}; noch ohne Song: " +
                    "${rest.playlists.size} Playlists, ${rest.favorites.size} Favoriten, " +
                    "${rest.collectionFavorites.size} Alben/Künstler, ${rest.playEvents.size} Hörverlauf, ${rest.wearSelections.size} Uhr",
            )
        }
        return rest
    }

    /**
     * Holt die gesicherten Bücher aus ARD Sounds, die es hier noch nicht gibt. Danach wendet [applyWhenLibraryChanges] ihren Fortschritt
     * an. Ohne Verbindung noch zweimal später, dann beim nächsten Start; eins, das ARD Sounds nicht mehr führt, bleibt gesichert.
     * Nur bei eingeschaltetem Schalter der Quelle (Standard: aus): Sonst fragte jeder Start ARD Sounds, obwohl der Nutzer die Quelle
     * nie eingeschaltet hat. Ist sie aus, wartet das Zurückholen, bis sie eingeschaltet wird.
     */
    internal suspend fun restoreArdBooks(pendingRows: () -> Collection<UserArdBookEntity> = { waiting?.pending?.ardBooks.orEmpty() }) {
        if (pendingRows().isEmpty()) return
        ardBooks.get().enabled.first { it }
        var wait = ARD_RETRY_MS
        repeat(ARD_ATTEMPTS) { attempt ->
            val missing = pendingRows()
            if (missing.isEmpty()) return
            if (attempt > 0) {
                delay(wait)
                wait *= 10
            }
            if (fetchArdBooks(missing)) return
        }
    }

    /** Holt die Bücher [rows] von ARD Sounds; `false`, wenn die Verbindung fehlte (der Rest bleibt dann ungeholt). */
    internal suspend fun fetchArdBooks(rows: Collection<UserArdBookEntity>): Boolean {
        for (row in rows) {
            try {
                ardBooks.get().restore(row.remoteId, row.addedAt, decodeArdParts(row.parts))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Buch aus ARD Sounds nicht zurückgeholt: ${row.remoteId}", e)
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Buch aus ARD Sounds nicht zurückgeholt: ${row.remoteId}", e)
            }
        }
        return true
    }

    /** Wendet nach und nach an, was wartet, sobald Scan oder Abgleich Bücher, Songs und Playlists anlegen. */
    private suspend fun applyWhenLibraryChanges() {
        database.invalidationTracker.createFlow("books", "songs", "playlists")
            .conflate()
            .collect {
                if (waiting == null) return@collect
                try {
                    mutex.withLock { waiting?.let { applyAndWait(it.saved, it.pending) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Nutzerdaten konnten nicht zurückgespielt werden", e)
                }
                delay(APPLY_INTERVAL_MS)
            }
    }

    /**
     * Vergisst gesicherten Fortschritt und gesicherte Lesezeichen der Folgen [remoteIds] von [serverId], deren Kennung sich
     * geändert hat (Quelle wechseln, Konzept 12.10): Das Spiegeln löscht nur, was es in der Hauptdatenbank noch gibt, und
     * die neuen Kennungen kommen von selbst hinzu.
     */
    suspend fun forgetProgress(serverId: String, remoteIds: Collection<String>) = mutex.withLock {
        remoteIds.chunked(MAX_ARGS).forEach {
            userDao.deleteProgressKeys(serverId, it)
            userDao.deleteBookmarkKeys(serverId, it)
            userDao.deleteEpisodeStarKeys(serverId, it)
        }
        // Der gemerkte Stand stimmt nicht mehr mit `user.db` überein; beim nächsten Spiegeln neu lesen.
        mirrored = null
    }

    /** [changedTables]: nur deren Teile neu lesen; `null` = alles. */
    internal suspend fun mirror(changedTables: Set<String>? = null) {
        val previous = mirrored ?: readUser()
        val now = SystemClock.elapsedRealtime()
        val base = mirrored.takeIf { changedTables != null && now - fullReadAt < FULL_READ_INTERVAL_MS }
        if (base == null) fullReadAt = now
        val current = readMain(base, changedTables.orEmpty()).keepingUnchangedPlaylists(previous)
        // Fehlt das Buch bzw. der Song, ist der Eintrag noch nicht angewendet (oder die Folge steht nicht mehr im Feed, die
        // Datei ist weg): behalten. Gelöscht wird nur, was es hier gibt und was hier entfernt wurde.
        // Was noch angewendet werden soll, bleibt ebenso, auch wenn sein Buch schon da ist.
        val stillWaiting = waiting?.pending
        val waitingProgress = stillWaiting?.progress.orEmpty().map { BookKey(it.serverId, it.remoteId) }.toSet()
        val waitingDownloads = stillWaiting?.episodeDownloads.orEmpty().map { BookKey(it.serverId, it.remoteId) }.toSet()
        val waitingBookmarks = stillWaiting?.bookmarks.orEmpty().map { it.key }.toSet()
        val waitingStars = stillWaiting?.episodeStars.orEmpty().map { BookKey(it.serverId, it.remoteId) }.toSet()
        val waitingArdBooks = stillWaiting?.ardBooks.orEmpty().map { it.remoteId }.toSet()
        val changes = current.changesSince(previous).let { changes ->
            val progressDeletes = changes.progress.deletes.filter {
                BookKey(it.serverId, it.remoteId) !in waitingProgress && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            val bookmarkDeletes = changes.bookmarks.deletes.filter {
                it.key !in waitingBookmarks && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            val downloadDeletes = changes.episodeDownloads.deletes.filter {
                BookKey(it.serverId, it.remoteId) !in waitingDownloads && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            val starDeletes = changes.episodeStars.deletes.filter {
                BookKey(it.serverId, it.remoteId) !in waitingStars && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            // Ein Buch aus ARD Sounds, das noch nicht zurückgeholt ist, bleibt gesichert, sonst fiele es vor dem Holen weg.
            val ardBookDeletes = changes.ardBooks.deletes.filter { it.remoteId !in waitingArdBooks }
            val songs = music.resolve((changes.favorites.deletes.map { it.song } + changes.playEvents.deletes.map { it.song }).toSet())
            changes.copy(
                progress = changes.progress.copy(deletes = progressDeletes),
                bookmarks = changes.bookmarks.copy(deletes = bookmarkDeletes),
                episodeDownloads = changes.episodeDownloads.copy(deletes = downloadDeletes),
                episodeStars = changes.episodeStars.copy(deletes = starDeletes),
                ardBooks = changes.ardBooks.copy(deletes = ardBookDeletes),
                favorites = changes.favorites.copy(deletes = changes.favorites.deletes.filter { it.song in songs }),
                collectionFavorites = changes.collectionFavorites.copy(
                    deletes = changes.collectionFavorites.deletes.filter { music.collectionState(it) != null },
                ),
                playEvents = changes.playEvents.copy(deletes = changes.playEvents.deletes.filter { it.song in songs }),
                wearSelections = changes.wearSelections.copy(deletes = changes.wearSelections.deletes.filter { music.wearTarget(it) != null }),
            )
        }
        if (!changes.isEmpty) userDao.apply(changes)
        mirrored = current
    }

    /** Liest die Teile, deren Tabellen in [changed] stehen, neu und nimmt die übrigen aus [base]; ohne [base] alles. */
    private suspend fun readMain(base: UserDataSnapshot?, changed: Set<String>): UserDataSnapshot {
        fun read(vararg tables: String) = base == null || tables.any { it in changed }
        val servers = read("sources")
        val spokenServers = read("spoken_servers")
        val feeds = read("podcast_feeds")
        val stats = read("listening_stats")
        val days = read("listening_days")
        // Welcher Fortschritt gesichert wird, hängt auch an den Audiobookshelf-Servern.
        val progress = read("book_progress", "spoken_servers")
        val bookmarks = read("bookmarks", "spoken_servers")
        // Welche Downloads gesichert werden, hängt an den gesicherten Abos.
        val downloads = read("book_tracks", "podcast_feeds")
        val stars = read("books")
        // Die Teile der Bücher aus ARD Sounds ändern sich beim Abgleich.
        val ards = read("books", "book_tracks")
        val musicData = if (read(*MUSIC_TABLES)) music.read() else null
        val fresh = UserDataSnapshot.ofMain(
            sources = if (servers) sourceDao.getServers() else emptyList(),
            spokenServers = if (spokenServers || progress || bookmarks) bookDao.servers() else emptyList(),
            feeds = if (feeds || downloads) podcastDao.keptFeeds() else emptyList(),
            listeningStats = if (stats) statsDao.all() else emptyList(),
            listeningDays = if (days) statsDao.allDays() else emptyList(),
            progress = if (progress) bookDao.keyedProgress() else emptyList(),
            playlists = musicData?.playlists.orEmpty(),
            playlistEntries = musicData?.playlistEntries.orEmpty(),
            favorites = musicData?.favorites.orEmpty(),
            playEvents = musicData?.playEvents.orEmpty(),
            wearSelections = musicData?.wearSelections.orEmpty(),
            episodeDownloads = if (downloads) podcastDao.keyedEpisodeDownloads() else emptyList(),
            collectionFavorites = musicData?.collectionFavorites.orEmpty(),
            bookmarks = if (bookmarks) bookDao.keyedBookmarks() else emptyList(),
            episodeStars = if (stars) podcastDao.keyedStarredEpisodes() else emptyList(),
            ardBooks = if (ards) bookDao.keyedBooksOf(ARD_BOOK_SERVER_ID) else emptyList(),
            ardParts = if (ards) bookDao.keyedPartsOf(ARD_BOOK_SERVER_ID) else emptyList(),
        )
        if (base == null) return fresh
        return UserDataSnapshot(
            servers = if (servers) fresh.servers else base.servers,
            spokenServers = if (spokenServers) fresh.spokenServers else base.spokenServers,
            podcasts = if (feeds) fresh.podcasts else base.podcasts,
            listeningStats = if (stats) fresh.listeningStats else base.listeningStats,
            listeningDays = if (days) fresh.listeningDays else base.listeningDays,
            progress = if (progress) fresh.progress else base.progress,
            playlists = if (musicData != null) fresh.playlists else base.playlists,
            playlistEntries = if (musicData != null) fresh.playlistEntries else base.playlistEntries,
            favorites = if (musicData != null) fresh.favorites else base.favorites,
            playEvents = if (musicData != null) fresh.playEvents else base.playEvents,
            wearSelections = if (musicData != null) fresh.wearSelections else base.wearSelections,
            episodeDownloads = if (downloads) fresh.episodeDownloads else base.episodeDownloads,
            collectionFavorites = if (musicData != null) fresh.collectionFavorites else base.collectionFavorites,
            bookmarks = if (bookmarks) fresh.bookmarks else base.bookmarks,
            episodeStars = if (stars) fresh.episodeStars else base.episodeStars,
            ardBooks = if (ards) fresh.ardBooks else base.ardBooks,
        )
    }

    private suspend fun readUser() = userDao.snapshot()

    private companion object {
        const val TAG = "UserDataBackup"
        const val RESTORED_MARKER = "user_data_restored"
        const val MIRROR_INTERVAL_MS = 60_000L
        const val FULL_READ_INTERVAL_MS = 30 * 60_000L
        val MUSIC_TABLES = arrayOf("songs", "albums", "artists", "playlists", "playlist_entries", "play_events", "wear_selections")
        const val APPLY_INTERVAL_MS = 5_000L
        const val ARD_ATTEMPTS = 3
        const val ARD_RETRY_MS = 30_000L
        const val MAX_ARGS = 500
        val MIRRORED_TABLES = arrayOf(
            "sources", "spoken_servers", "podcast_feeds", "listening_stats", "listening_days", "book_progress", "bookmarks",
            "songs", "albums", "artists", "playlists", "playlist_entries", "play_events", "wear_selections", "book_tracks",
            // Nur für die gemerkten Folgen; gelesen wird dann allein deren Teil.
            "books",
        )
    }
}

/** Server, Abos und Playlists einer Komplett-Sicherung; beim Einspielen die neu angelegten. */
data class BackupCounts(
    val musicServers: Int = 0,
    val audiobookServers: Int = 0,
    val podcasts: Int = 0,
    val playlists: Int = 0,
)

/** Gesichertes, dessen Buch, Folge, Song, Album oder Playlist es (noch) nicht gibt; Playlists über ihren Schlüssel. */
internal data class Pending(
    val progress: List<UserBookProgressEntity> = emptyList(),
    val bookmarks: List<UserBookmarkEntity> = emptyList(),
    val playlists: List<String> = emptyList(),
    val favorites: List<UserFavoriteEntity> = emptyList(),
    val collectionFavorites: List<UserCollectionFavoriteEntity> = emptyList(),
    val playEvents: List<UserPlayEventEntity> = emptyList(),
    val wearSelections: List<UserWearSelectionEntity> = emptyList(),
    val episodeDownloads: List<UserEpisodeDownloadEntity> = emptyList(),
    val episodeStars: List<UserEpisodeStarEntity> = emptyList(),
    val ardBooks: List<UserArdBookEntity> = emptyList(),
    /** Zurückgespielte Abos, deren Folgen noch nicht da sind (siehe `PodcastDao.excludeAllFromAutomatic`). */
    val restoredFeeds: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = progress.isEmpty() && bookmarks.isEmpty() && playlists.isEmpty() && favorites.isEmpty() && collectionFavorites.isEmpty() && playEvents.isEmpty() &&
            wearSelections.isEmpty() && episodeDownloads.isEmpty() && episodeStars.isEmpty() && ardBooks.isEmpty() && restoredFeeds.isEmpty()

    companion object {
        fun of(saved: UserDataSnapshot) = Pending(
            progress = saved.progress.values.toList(),
            bookmarks = saved.bookmarks.values.toList(),
            playlists = saved.playlists.keys.toList(),
            favorites = saved.favorites.values.toList(),
            collectionFavorites = saved.collectionFavorites.values.toList(),
            playEvents = saved.playEvents.values.toList(),
            wearSelections = saved.wearSelections.values.toList(),
            episodeDownloads = saved.episodeDownloads.values.toList(),
            episodeStars = saved.episodeStars.values.toList(),
            ardBooks = saved.ardBooks.values.toList(),
        )
    }
}
