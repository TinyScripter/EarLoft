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
 * Credentials of protected feeds per subscription (concept 12.10 "Protected feeds"), encrypted in the [SecretStore] under
 * `feed:<feedId>:auth`. Like all passwords, they are not included in the backup, the OPML or database export, or sent to the
 * watch. The feed fetch gets them per subscription ([forFeed]); for streams, downloads and images [start] keeps the [FeedAuthRegistry]
 * up to date. When a subscription disappears (ended, trial subscription cleaned up), its credentials disappear too.
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

    /** Decrypted credentials per subscription, `null` = none; only subscriptions that have already been looked up. */
    private val known = HashMap<String, FeedAuth?>()

    /** The subscriptions at the last look into the database; only one that was there and is missing is really gone (not a new one). */
    private var lastFeedIds: Set<String> = emptySet()
    private val changes = MutableStateFlow(0)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            dao.observeAllFeeds().map { feeds -> feeds.map { it.feedId }.toSet() }.distinctUntilChanged().collect(::sync)
        }
    }

    suspend fun forFeed(feedId: String): FeedAuth? = mutex.withLock { lookup(feedId) }

    /** For the subscription settings: the current credentials, refreshed after every change. */
    fun observe(feedId: String): Flow<FeedAuth?> = changes.map { forFeed(feedId) }.distinctUntilChanged()

    /** Stores ([auth]) or deletes (`null`) the subscription's credentials. */
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
        Log.w(TAG, "Credentials of a subscription are unreadable", e)
        null
    }

    private companion object {
        const val TAG = "FeedCredentials"
        const val SCOPE = "scope"
        const val USER = "user"
        const val PASSWORD = "password"
    }
}
