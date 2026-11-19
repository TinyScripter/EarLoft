package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** [ArdBooks] for tests that get their data from [graphql]. */
fun testArdBooks(context: Context, db: EarLoftDatabase, graphql: HttpUrl, settings: SpokenSettings = SpokenSettings(context)): ArdBooks {
    val client = OkHttpClient()
    val secrets = object : SecretStore {
        override suspend fun get(key: String): String? = null
        override suspend fun put(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }
    val api = AbsApi(client)
    val covers = BookCovers(
        context, db.bookDao(), db.podcastDao(), testAudiobookshelfServers(db.bookDao(), secrets, api), api, PodcastFeedApi(client), LocalBookScanner(context),
    )
    return ArdBooks(db.bookDao(), ArdSoundsBooks(ArdSoundsApi(client, graphql)), settings, covers, { _, _ -> })
}
