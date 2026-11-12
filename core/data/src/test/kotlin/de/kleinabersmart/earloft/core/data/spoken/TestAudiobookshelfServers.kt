package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import okhttp3.OkHttpClient

/** [AudiobookshelfServers] für Tests; Anmeldung und Erneuerung gehen über [client]. */
fun testAudiobookshelfServers(
    dao: BookDao,
    secrets: SecretStore,
    api: AbsApi,
    registry: AbsServerRegistry = AbsServerRegistry(),
    client: OkHttpClient = OkHttpClient(),
): AudiobookshelfServers {
    val authApi = AbsAuthApi(client)
    return AudiobookshelfServers(dao, secrets, api, authApi, AbsTokenRenewal(secrets, registry, authApi), registry, CredentialsStamps(secrets))
}
