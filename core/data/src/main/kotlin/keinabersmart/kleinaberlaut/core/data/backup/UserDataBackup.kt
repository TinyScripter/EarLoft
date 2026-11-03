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
import keinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
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
    /** Erst gebraucht, wenn wirklich etwas zurückgespielt wurde. */
    private val registry: Lazy<DataSourceRegistry>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

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
            database.invalidationTracker.createFlow(*MIRRORED_TABLES)
                .conflate()
                .collect {
                    try {
                        mirror()
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
            Log.i(
                TAG,
                "Nutzerdaten zurückgespielt: ${saved.servers.size} Musik-Server, ${saved.spokenServers.size} ABS-Server, " +
                    "${saved.podcasts.size} Podcasts, ${saved.listeningStats.size} Statistik-Einträge",
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
        }
    }

    internal suspend fun mirror() {
        val current = readMain()
        val previous = mirrored ?: readUser()
        val changes = current.changesSince(previous)
        if (!changes.isEmpty) userDao.apply(changes)
        mirrored = current
    }

    private suspend fun readMain() = UserDataSnapshot.ofMain(
        sources = sourceDao.getByType(SourceType.SUBSONIC),
        spokenServers = bookDao.servers(),
        feeds = podcastDao.feeds(),
        listeningStats = statsDao.all(),
        listeningDays = statsDao.allDays(),
    )

    private suspend fun readUser() = UserDataSnapshot.ofUser(
        servers = userDao.servers(),
        spokenServers = userDao.spokenServers(),
        podcasts = userDao.podcasts(),
        listeningStats = userDao.listeningStats(),
        listeningDays = userDao.listeningDays(),
    )

    private companion object {
        const val TAG = "UserDataBackup"
        const val RESTORED_MARKER = "user_data_restored"
        const val MIRROR_INTERVAL_MS = 60_000L
        val MIRRORED_TABLES = arrayOf("sources", "spoken_servers", "podcast_feeds", "listening_stats", "listening_days")
    }
}
