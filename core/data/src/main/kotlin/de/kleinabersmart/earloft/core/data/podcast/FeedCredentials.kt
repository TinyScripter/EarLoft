package de.kleinabersmart.earloft.core.data.podcast

import android.util.Log
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.network.podcast.FeedAuth
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zugangsdaten geschützter Feeds je Abo (Konzept 12.10 „Geschützte Feeds“), verschlüsselt im [SecretStore] unter
 * `feed:<feedId>:auth`. Wie alle Passwörter gehen sie nicht ins Backup, nicht in OPML- oder Datenbank-Export und nicht an die
 * Uhr. Der Feed-Abruf holt sie je Abo ([forFeed]); für Streams, Downloads und Bilder hält [start] die [FeedAuthRegistry]
 * aktuell. Verschwindet ein Abo (beendet, Probeabo aufgeräumt), verschwinden auch seine Zugangsdaten.
 */
@Singleton
class FeedCredentials @Inject constructor(
    private val secrets: SecretStore,
    private val dao: PodcastDao,
    private val registry: FeedAuthRegistry,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    /** Entschlüsselte Zugangsdaten je Abo, `null` = keine; nur Abos, die schon nachgesehen wurden. */
    private val known = HashMap<String, FeedAuth?>()

    /** Die Abos beim letzten Blick in die Datenbank; nur wer dort stand und fehlt, ist wirklich weg (nicht ein neues). */
    private var lastFeedIds: Set<String> = emptySet()
    private val changes = MutableStateFlow(0)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            dao.observeAllFeeds().map { feeds -> feeds.map { it.feedId }.toSet() }.distinctUntilChanged().collect(::sync)
        }
    }

    suspend fun forFeed(feedId: String): FeedAuth? = mutex.withLock { lookup(feedId) }

    /** Für die Abo-Einstellungen: die aktuellen Zugangsdaten, nach jeder Änderung neu. */
    fun observe(feedId: String): Flow<FeedAuth?> = changes.map { forFeed(feedId) }.distinctUntilChanged()

    /** Speichert ([auth]) bzw. löscht (`null`) die Zugangsdaten des Abos. */
    suspend fun set(feedId: String, auth: FeedAuth?) {
        mutex.withLock {
            if (auth == null) secrets.remove(key(feedId)) else secrets.put(key(feedId), auth.encode())
            known[feedId] = auth
            publish()
        }
        changes.value++
    }

    private suspend fun sync(feedIds: Set<String>) {
        mutex.withLock {
            for (feedId in feedIds) lookup(feedId)
            for (feedId in lastFeedIds - feedIds) {
                known.remove(feedId)
                secrets.remove(key(feedId))
            }
            lastFeedIds = feedIds
            publish()
        }
        changes.value++
    }

    private suspend fun lookup(feedId: String): FeedAuth? {
        if (feedId in known) return known[feedId]
        return secrets.get(key(feedId))?.let(::decode).also { known[feedId] = it }
    }

    private fun publish() {
        registry.replaceAll(known.mapNotNull { (feedId, auth) -> auth?.let { feedId to it } }.toMap())
    }

    private fun key(feedId: String) = "feed:$feedId:auth"

    private fun FeedAuth.encode(): String =
        JSONObject().put(SCOPE, scope.toString()).put(USER, username).put(PASSWORD, password).toString()

    private fun decode(stored: String): FeedAuth? = try {
        val json = JSONObject(stored)
        json.getString(SCOPE).toHttpUrlOrNull()?.let { FeedAuth(it, json.getString(USER), json.getString(PASSWORD)) }
    } catch (e: JSONException) {
        Log.w(TAG, "Zugangsdaten eines Abos sind unlesbar", e)
        null
    }

    private companion object {
        const val TAG = "FeedCredentials"
        const val SCOPE = "scope"
        const val USER = "user"
        const val PASSWORD = "password"
    }
}
