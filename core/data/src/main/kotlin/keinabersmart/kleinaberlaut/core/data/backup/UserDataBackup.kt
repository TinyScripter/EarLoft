package keinabersmart.kleinaberlaut.core.data.backup

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.dao.StatsDao
import keinabersmart.kleinaberlaut.core.database.dao.UserDataDao
import keinabersmart.kleinaberlaut.core.database.entity.UserBookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserFavoriteEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserWearSelectionEntity
import keinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält `user.db` ([keinabersmart.kleinaberlaut.core.database.UserDatabase]) auf dem Stand der Hauptdatenbank (Review K9).
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
 */
@Singleton
class UserDataBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KleinaberlautDatabase,
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

    /** Spiegeln und Anwenden lesen und schreiben dieselben Zeilen. */
    private val mutex = Mutex()

    /** Der zuletzt nach `user.db` geschriebene Stand; spart das erneute Lesen von `user.db` bei jedem Abgleich. */
    private var mirrored: UserDataSnapshot? = null

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
            }
            val saved = readUser()
            val pending = mutex.withLock { applySaved(saved, Pending.of(saved)) }
            if (!pending.isEmpty) launch { applyWhenLibraryChanges(saved, pending) }
            database.invalidationTracker.createFlow(*MIRRORED_TABLES)
                .conflate()
                .collect {
                    try {
                        mutex.withLock { mirror() }
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

    internal suspend fun mirror() {
        val previous = mirrored ?: readUser()
        val current = readMain().keepingUnchangedPlaylists(previous)
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

    private suspend fun readMain(): UserDataSnapshot {
        val musicData = music.read()
        return UserDataSnapshot.ofMain(
            sources = sourceDao.getByType(SourceType.SUBSONIC),
            spokenServers = bookDao.servers(),
            feeds = podcastDao.keptFeeds(),
            listeningStats = statsDao.all(),
            listeningDays = statsDao.allDays(),
            progress = bookDao.keyedProgress(),
            playlists = musicData.playlists,
            playlistEntries = musicData.playlistEntries,
            favorites = musicData.favorites,
            playEvents = musicData.playEvents,
            wearSelections = musicData.wearSelections,
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
