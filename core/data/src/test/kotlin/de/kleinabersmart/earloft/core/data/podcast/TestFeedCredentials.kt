package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Geheimnisse im Speicher statt im Keystore. */
internal class MemorySecretStore : SecretStore {
    val values = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

/** Zugangsdaten geschützter Feeds für Tests; ohne [FeedCredentials.start] beobachtet niemand die Abos. */
internal fun testFeedCredentials(
    db: EarLoftDatabase,
    secrets: SecretStore = MemorySecretStore(),
    registry: FeedAuthRegistry = FeedAuthRegistry(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) = FeedCredentials(secrets, db.podcastDao(), registry, scope)
