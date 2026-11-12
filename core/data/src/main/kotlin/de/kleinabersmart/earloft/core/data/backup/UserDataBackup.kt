package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
 * - **Musik** ([MusicBackup]) ebenso, sobald ihre Songs, Alben und Playlists wieder da sind: lokale Playlists (werden beim
 *   Zurückspielen leer angelegt), Favoriten von Songs ohne Server, Hörverlauf und die Auswahl für die Uhr.
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
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Fertig, sobald das Zurückspielen beim Start gelaufen ist, auch wenn es fehlschlug. */
    private val restoreFinished = CompletableDeferred<Unit>()

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
                restoreOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ohne Merker: Beim nächsten Start wird es erneut versucht. Gespiegelt wird trotzdem nicht, damit eine noch
                // nicht zurückgespielte Sicherung nicht vom leeren Stand überschrieben wird.
                Log.e(TAG, "Nutzerdaten konnten nicht zurückgespielt werden", e)
                return@launch
            } finally {
                restoreFinished.complete(Unit)
            }
            val saved = readUser()
            val pending = mutex.withLock { applySaved(saved, Pending.of(saved)) }
            if (!pending.isEmpty) launch { applyWhenLibraryChanges(saved, pending) }
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

    private suspend fun restoreOnce() {
        val marker = File(context.noBackupFilesDir, RESTORED_MARKER)
        if (withContext(Dispatchers.IO) { marker.exists() }) return
        val saved = readUser()
        if (!saved.isEmpty) {
            restore(saved)
            // Die Liste der Server ohne Passwort entsteht beim Laden; die Einstellungen sollen „Passwort neu eingeben“ zeigen.
            if (saved.servers.isNotEmpty()) registry.get().reload()
            if (saved.servers.isNotEmpty() || saved.spokenServers.isNotEmpty()) restoreNotice.serversRestored()
            Log.i(
                TAG,
                "Nutzerdaten zurückgespielt: ${saved.servers.size} Musik-Server, ${saved.spokenServers.size} ABS-Server, " +
                    "${saved.podcasts.size} Podcasts, ${saved.listeningStats.size} Statistik-Einträge, " +
                    "${saved.progress.size} Fortschritte, ${saved.playlists.size} Playlists, ${saved.favorites.size} Favoriten, " +
                    "${saved.playEvents.size} Einträge im Hörverlauf, ${saved.wearSelections.size} Auswahlen für die Uhr " +
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

    /** Wendet von [saved] an, was davon in [pending] steht; liefert, was (noch) kein Buch, keinen Song usw. hat. */
    internal suspend fun applySaved(saved: UserDataSnapshot, pending: Pending): Pending {
        val progress = applyProgress(pending.progress)
        val rest = database.withTransaction {
            Pending(
                progress = progress,
                playlists = music.applyPlaylists(saved, pending.playlists),
                favorites = music.applyFavorites(pending.favorites),
                playEvents = music.applyPlayEvents(pending.playEvents),
                wearSelections = music.applyWearSelections(pending.wearSelections),
            )
        }
        val found = listOf(
            "Favoriten" to pending.favorites.size - rest.favorites.size,
            "Hörverlauf" to pending.playEvents.size - rest.playEvents.size,
            "Uhr" to pending.wearSelections.size - rest.wearSelections.size,
        ).filter { it.second > 0 }
        if (found.isNotEmpty() || rest.playlists != pending.playlists) {
            Log.i(
                TAG,
                "Musik zurückgespielt: ${found.joinToString { "${it.first} ${it.second}" }}; noch ohne Song: " +
                    "${rest.playlists.size} Playlists, ${rest.favorites.size} Favoriten, ${rest.playEvents.size} Hörverlauf, " +
                    "${rest.wearSelections.size} Uhr",
            )
        }
        return rest
    }

    /** Wendet [initial] nach und nach an, sobald Scan oder Abgleich Bücher, Songs und Playlists anlegen. */
    private suspend fun applyWhenLibraryChanges(saved: UserDataSnapshot, initial: Pending) {
        var pending = initial
        database.invalidationTracker.createFlow("books", "songs", "playlists")
            .conflate()
            .takeWhile { !pending.isEmpty }
            .collect {
                try {
                    pending = mutex.withLock { applySaved(saved, pending) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Nutzerdaten konnten nicht zurückgespielt werden", e)
                }
                delay(APPLY_INTERVAL_MS)
            }
    }

    /**
     * Vergisst gesicherten Fortschritt der Folgen [remoteIds] von [serverId], deren Kennung sich geändert hat (Quelle
     * wechseln, Konzept 12.10): Das Spiegeln löscht nur, was es in der Hauptdatenbank noch gibt, und die neuen Kennungen
     * kommen von selbst hinzu.
     */
    suspend fun forgetProgress(serverId: String, remoteIds: Collection<String>) = mutex.withLock {
        remoteIds.chunked(MAX_ARGS).forEach { userDao.deleteProgressKeys(serverId, it) }
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
        val changes = current.changesSince(previous).let { changes ->
            val progressDeletes = changes.progress.deletes.filter { bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty() }
            val songs = music.resolve((changes.favorites.deletes.map { it.song } + changes.playEvents.deletes.map { it.song }).toSet())
            changes.copy(
                progress = changes.progress.copy(deletes = progressDeletes),
                favorites = changes.favorites.copy(deletes = changes.favorites.deletes.filter { it.song in songs }),
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
        val musicData = if (read(*MUSIC_TABLES)) music.read() else null
        val fresh = UserDataSnapshot.ofMain(
            sources = if (servers) sourceDao.getServers() else emptyList(),
            spokenServers = if (spokenServers || progress) bookDao.servers() else emptyList(),
            feeds = if (feeds) podcastDao.keptFeeds() else emptyList(),
            listeningStats = if (stats) statsDao.all() else emptyList(),
            listeningDays = if (days) statsDao.allDays() else emptyList(),
            progress = if (progress) bookDao.keyedProgress() else emptyList(),
            playlists = musicData?.playlists.orEmpty(),
            playlistEntries = musicData?.playlistEntries.orEmpty(),
            favorites = musicData?.favorites.orEmpty(),
            playEvents = musicData?.playEvents.orEmpty(),
            wearSelections = musicData?.wearSelections.orEmpty(),
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
        )
    }

    private suspend fun readUser() = UserDataSnapshot.ofUser(
        servers = userDao.servers(),
        spokenServers = userDao.spokenServers(),
        podcasts = userDao.podcasts(),
        listeningStats = userDao.listeningStats(),
        listeningDays = userDao.listeningDays(),
        progress = userDao.progress(),
        playlists = userDao.playlists(),
        playlistEntries = userDao.playlistEntries(),
        favorites = userDao.favorites(),
        playEvents = userDao.playEvents(),
        wearSelections = userDao.wearSelections(),
    )

    private companion object {
        const val TAG = "UserDataBackup"
        const val RESTORED_MARKER = "user_data_restored"
        const val MIRROR_INTERVAL_MS = 60_000L
        const val FULL_READ_INTERVAL_MS = 30 * 60_000L
        val MUSIC_TABLES = arrayOf("songs", "playlists", "playlist_entries", "play_events", "wear_selections")
        const val APPLY_INTERVAL_MS = 5_000L
        const val MAX_ARGS = 500
        val MIRRORED_TABLES = arrayOf(
            "sources", "spoken_servers", "podcast_feeds", "listening_stats", "listening_days", "book_progress",
            "songs", "playlists", "playlist_entries", "play_events", "wear_selections",
        )
    }
}

/** Gesichertes, dessen Buch, Song, Album oder Playlist es (noch) nicht gibt; Playlists über ihren Schlüssel. */
internal data class Pending(
    val progress: List<UserBookProgressEntity> = emptyList(),
    val playlists: List<String> = emptyList(),
    val favorites: List<UserFavoriteEntity> = emptyList(),
    val playEvents: List<UserPlayEventEntity> = emptyList(),
    val wearSelections: List<UserWearSelectionEntity> = emptyList(),
) {
    val isEmpty: Boolean
        get() = progress.isEmpty() && playlists.isEmpty() && favorites.isEmpty() && playEvents.isEmpty() && wearSelections.isEmpty()

    companion object {
        fun of(saved: UserDataSnapshot) = Pending(
            progress = saved.progress.values.toList(),
            playlists = saved.playlists.keys.toList(),
            favorites = saved.favorites.values.toList(),
            playEvents = saved.playEvents.values.toList(),
            wearSelections = saved.wearSelections.values.toList(),
        )
    }
}
