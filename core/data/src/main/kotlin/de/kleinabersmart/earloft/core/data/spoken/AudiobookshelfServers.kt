package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.SpokenLibraryEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsLoginOptions
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsOpenIdLogin
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import de.kleinabersmart.earloft.core.model.SpokenLibrary
import de.kleinabersmart.earloft.core.model.SpokenServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface AddServerResult {
    data class Added(val serverId: String) : AddServerResult
    data object InvalidUrl : AddServerResult
    data object Unreachable : AddServerResult
    data object AuthFailed : AddServerResult
    /** Der Server hat die Anmeldung per OpenID nicht abgeschlossen (abgelehnt, abgelaufen, fremde Rückleitung). */
    data object OpenIdFailed : AddServerResult
    data object NoBookLibrary : AddServerResult
}

/** Beginn einer Anmeldung per OpenID ([AudiobookshelfServers.beginOpenId]). */
sealed interface BeginOpenIdResult {
    data class Started(val login: AbsOpenIdLogin) : BeginOpenIdResult
    data object InvalidUrl : BeginOpenIdResult
    data object Unreachable : BeginOpenIdResult
    /** Audiobookshelf lässt [redirectUri] nicht zu: Sie gehört in „Allowed Mobile Redirect URIs“. */
    data class RedirectNotAllowed(val redirectUri: String) : BeginOpenIdResult
}

/** Ein Server zum Bearbeiten; API-Key und Tokens verlassen den [SecretStore] nicht. */
data class EditableAbsServer(val name: String, val baseUrl: String, val hasKey: Boolean, val openId: Boolean = false)

/**
 * Ein Server, wie das Handy ihn der Uhr übergibt (ohne Schlüssel, Wear-Konzept 16.2). [credentialsStamp] ändert sich mit dem
 * Schlüssel ([CredentialsStamps]); `null`, wenn das Handy keinen hat.
 */
data class MirroredAbsServer(val serverId: String, val name: String, val baseUrl: String, val credentialsStamp: String? = null)

/**
 * Eingerichtete Audiobookshelf-Server (Konzept 12.1). Angemeldet per API-Key oder per OpenID (12.11); API-Key bzw. Access- und
 * Refresh-Token liegen im [SecretStore] ([AbsSecrets]), erneuert von [AbsTokenRenewal].
 */
@Singleton
class AudiobookshelfServers @Inject constructor(
    private val bookDao: BookDao,
    private val secretStore: SecretStore,
    private val api: AbsApi,
    private val authApi: AbsAuthApi,
    private val renewal: AbsTokenRenewal,
    private val registry: AbsServerRegistry,
    private val stamps: CredentialsStamps,
) {
    val servers: Flow<List<SpokenServer>> = combine(bookDao.observeServers(), bookDao.observeLibraries()) { list, libraries ->
        val byServer = libraries.groupBy { it.serverId }
        list.map { server ->
            val hidden = server.hiddenLibraryIds
            SpokenServer(
                server.serverId, server.name, server.baseUrl, server.lastFullSyncAt, server.enabled, server.openId,
                libraries = byServer[server.serverId].orEmpty().map { SpokenLibrary(it.serverId, it.libraryId, it.name, it.libraryId !in hidden) },
            )
        }
    }

    /**
     * Wählt eine Buch-Bibliothek ab bzw. wieder an (`books_improvements.md` M3); ihre Bücher sind sofort aus- bzw. eingeblendet.
     * Abgewählt bleiben sie samt Downloads, Fortschritt und Lesezeichen gespeichert, werden aber nicht abgeglichen (wie bei einem
     * ausgeschalteten Server). Die letzte gezeigte bleibt: `false`, wenn sie abgewählt werden sollte.
     */
    suspend fun setLibraryShown(serverId: String, libraryId: String, shown: Boolean): Boolean = librariesMutex.withLock {
        // Lesen und Schreiben unter einem Schloss: Schnelle Klicks auf mehrere Bibliotheken überschrieben sich sonst gegenseitig.
        val entity = bookDao.server(serverId) ?: return@withLock false
        val hidden = if (shown) entity.hiddenLibraryIds - libraryId else entity.hiddenLibraryIds + libraryId
        val known = bookDao.libraries(serverId).map { it.libraryId }
        if (known.isNotEmpty() && known.all { it in hidden }) return@withLock false
        bookDao.setHiddenLibraries(serverId, hidden.sorted().joinToString(","))
        true
    }

    /**
     * Handy: die eingeschalteten Server für die Uhr. Ausgeschaltete fehlen, ihre Bücher sind am Handy ausgeblendet und fallen von
     * der Uhr. Ein neuer Schlüssel schreibt auch den Server neu ([connect]); damit kommt ein neuer Stempel. Bei OpenID hängt der
     * Stempel an der Anmeldung, nicht am stündlich neuen Access-Token: Den holt sich die Uhr selbst, wenn er abgelaufen ist.
     */
    val mirrored: Flow<List<MirroredAbsServer>> = bookDao.observeServers().map { list ->
        list.filter { it.enabled }.map {
            val secret = if (it.openId) secretStore.get(AbsSecrets.login(it.serverId)) else secretStore.get(tokenKey(it.serverId))
            MirroredAbsServer(it.serverId, it.name, it.baseUrl, stamps.absStamp(it.serverId, secret))
        }
    }

    /**
     * Server, deren API-Key bzw. Anmeldung fehlt (z. B. nach einer Wiederherstellung auf einem anderen Gerät, oder der
     * Refresh-Token ist abgelaufen); der Nutzer muss beim Bearbeiten den Key neu eingeben bzw. sich neu anmelden. Neu berechnet,
     * wenn sich die Server ändern – das Speichern eines Keys ändert sie mit – und wenn eine Anmeldung abläuft.
     */
    val serversWithoutKey: Flow<Set<String>> = combine(bookDao.observeServers(), renewal.expired) { list, _ ->
        list.filter { secretStore.get(tokenKey(it.serverId)) == null }.mapTo(HashSet()) { it.serverId }
    }

    private val mutex = Mutex()
    private val librariesMutex = Mutex()

    @Volatile
    private var loaded = false

    /** Alle eingeschalteten Server, deren Key sich lesen lässt. */
    suspend fun all(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().filter { it.enabled }.mapNotNull { registry[it.serverId] }
    }

    suspend fun server(serverId: String): AbsServer? {
        ensureLoaded()
        return registry[serverId]
    }

    /** Wie [all], aber auch ausgeschaltete Server, wie bei [server]. */
    suspend fun allIncludingDisabled(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().mapNotNull { registry[it.serverId] }
    }

    suspend fun add(name: String, baseUrlInput: String, apiKey: String): AddServerResult =
        connect(null, name, baseUrlInput, Credentials.ApiKey(apiKey))

    /**
     * Ändert Name, Adresse oder API-Key eines Servers, nur wenn die Verbindung mit den neuen Angaben klappt.
     * Die `serverId` bleibt, damit Bücher, Fortschritt und Downloads erhalten bleiben. [apiKey] leer = der
     * gespeicherte Key bleibt. Zeigt die Adresse auf eine andere Bibliothek, räumt der nächste Vollabgleich auf.
     */
    suspend fun update(serverId: String, name: String, baseUrlInput: String, apiKey: String): AddServerResult =
        connect(serverId, name, baseUrlInput, Credentials.ApiKey(apiKey))

    /** Bietet der Server die Anmeldung per OpenID an? `null`, wenn die Adresse ungültig oder kein Audiobookshelf ist. */
    suspend fun loginOptions(baseUrlInput: String): AbsLoginOptions? {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return null
        return try {
            authApi.loginOptions(baseUrl)
        } catch (e: IOException) {
            Log.d(TAG, "Anmeldearten von $baseUrl nicht lesbar", e)
            null
        }
    }

    /** Erster Schritt der Anmeldung per OpenID (Konzept 12.11); danach meldet sich der Nutzer im Browser an. */
    suspend fun beginOpenId(baseUrlInput: String, redirectUri: String): BeginOpenIdResult {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return BeginOpenIdResult.InvalidUrl
        return try {
            BeginOpenIdResult.Started(authApi.beginOpenId(baseUrl, redirectUri))
        } catch (e: AbsException) {
            Log.w(TAG, "OpenID bei $baseUrl nicht möglich", e)
            if (e.code == 400) BeginOpenIdResult.RedirectNotAllowed(redirectUri) else BeginOpenIdResult.Unreachable
        } catch (e: IOException) {
            Log.w(TAG, "Server $baseUrl nicht erreichbar", e)
            BeginOpenIdResult.Unreachable
        }
    }

    /**
     * Schließt die Anmeldung per OpenID mit der Rückleitung [redirect] des Browsers ab und richtet den Server ein ([serverId]
     * `null`) bzw. meldet ihn neu an. Wie bei [update] bleiben `serverId`, Bücher und Fortschritt.
     */
    suspend fun connectOpenId(serverId: String?, name: String, login: AbsOpenIdLogin, redirect: String): AddServerResult {
        val redirectUrl = AbsAuthApi.redirectUrl(redirect) ?: return AddServerResult.OpenIdFailed
        val tokens = try {
            authApi.finishOpenId(login, redirectUrl)
        } catch (e: AbsException) {
            Log.w(TAG, "OpenID bei ${login.baseUrl} abgelehnt", e)
            return AddServerResult.OpenIdFailed
        } catch (e: IOException) {
            Log.w(TAG, "Server ${login.baseUrl} nicht erreichbar", e)
            return AddServerResult.Unreachable
        }
        return connect(serverId, name, login.baseUrl.toString(), Credentials.OpenId(tokens.accessToken, tokens.refreshToken))
    }

    /** Schaltet den Server ein oder aus; ausgeschaltet bleiben Bücher und Downloads erhalten, sind aber ausgeblendet. */
    suspend fun setEnabled(serverId: String, enabled: Boolean) {
        bookDao.setServerEnabled(serverId, enabled)
    }

    /**
     * Der Token für Empfänger, die selbst nicht erneuern können (Cast): bei OpenID noch mindestens [minValiditySeconds]
     * gültig, wenn nötig vorher erneuert. `null`, wenn der Server nicht eingerichtet ist oder der Token fehlt.
     */
    suspend fun freshToken(serverId: String, minValiditySeconds: Long = FRESH_FOR_SECONDS): String? {
        ensureLoaded()
        return renewal.fresh(serverId, minValiditySeconds)
    }

    /** Eingeschaltete Server ohne gespeicherten API-Key (nach dem Wiederherstellen auf einem neuen Gerät). */
    suspend fun withoutKey(): List<String> {
        val missing = serversWithoutKey.first()
        return bookDao.servers().filter { it.enabled && it.serverId in missing }.map { it.serverId }
    }

    suspend fun editable(serverId: String): EditableAbsServer? {
        val entity = bookDao.server(serverId) ?: return null
        return EditableAbsServer(entity.name, entity.baseUrl, hasKey = secretStore.get(tokenKey(serverId)) != null, openId = entity.openId)
    }

    private sealed interface Credentials {
        /** Leer: Der gespeicherte API-Key bzw. die gespeicherte Anmeldung bleibt. */
        data class ApiKey(val key: String) : Credentials
        data class OpenId(val accessToken: String, val refreshToken: String?) : Credentials
    }

    private suspend fun connect(existingId: String?, name: String, baseUrlInput: String, credentials: Credentials): AddServerResult {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return AddServerResult.InvalidUrl
        val existing = existingId?.let { bookDao.server(it) }
        if (existingId != null && existing == null) return AddServerResult.InvalidUrl
        val keep = credentials is Credentials.ApiKey && credentials.key.isBlank()
        // Damit ein beim Behalten abgelaufener Access-Token bei der Prüfung erneuert wird (AbsTokenAuthenticator).
        if (keep) ensureLoaded()
        val key = when (credentials) {
            is Credentials.ApiKey -> credentials.key.trim().ifEmpty { existingId?.let { secretStore.get(tokenKey(it)) }.orEmpty() }
            is Credentials.OpenId -> credentials.accessToken
        }
        if (key.isEmpty()) return AddServerResult.AuthFailed
        val candidate = AbsServer(existingId ?: UUID.randomUUID().toString(), baseUrl, key)
        val libraries = try {
            api.ping(candidate)
            api.me(candidate)
            api.libraries(candidate).filter { it.mediaType == "book" }
        } catch (e: AbsException) {
            Log.w(TAG, "Server $baseUrl abgelehnt", e)
            return if (e.isAuthError) AddServerResult.AuthFailed else AddServerResult.Unreachable
        } catch (e: IOException) {
            Log.w(TAG, "Server $baseUrl nicht erreichbar", e)
            return AddServerResult.Unreachable
        }
        val library = libraries.firstOrNull { it.id !in existing?.hiddenLibraryIds.orEmpty() } ?: libraries.firstOrNull()
            ?: return AddServerResult.NoBookLibrary

        val serverId = candidate.serverId
        when (credentials) {
            // Beim Behalten nichts schreiben: Ein Access-Token kann sich während der Prüfung erneuert haben.
            is Credentials.ApiKey -> if (!keep) {
                secretStore.put(tokenKey(serverId), key)
                secretStore.remove(AbsSecrets.refresh(serverId))
                secretStore.remove(AbsSecrets.login(serverId))
            }
            is Credentials.OpenId -> {
                secretStore.put(tokenKey(serverId), key)
                credentials.refreshToken?.let { secretStore.put(AbsSecrets.refresh(serverId), it) }
                    ?: secretStore.remove(AbsSecrets.refresh(serverId))
                secretStore.put(AbsSecrets.login(serverId), UUID.randomUUID().toString())
                renewal.forget(serverId)
            }
        }
        // Sync-Generation, Zeitpunkt und Schalter bleiben, sonst hielte der Mark-and-Sweep alles für neu.
        bookDao.upsertServer(
            (existing ?: SpokenServerEntity(serverId, name, baseUrl.toString())).copy(
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                libraryId = library.id,
                openId = if (keep) existing?.openId == true else credentials is Credentials.OpenId,
            ),
        )
        // Alle Buch-Bibliotheken; ohne abgewählte sind alle dabei (M3). Die Auswahl steht danach in den Einstellungen.
        bookDao.replaceLibraries(serverId, libraries.mapIndexed { index, it -> SpokenLibraryEntity(serverId, it.id, it.name, index) })
        registry.register(candidate.copy(token = secretStore.get(tokenKey(serverId)) ?: key))
        return AddServerResult.Added(serverId)
    }

    /** Entfernt den Server samt Büchern, Kapiteln und Fortschritt. Der Fortschritt auf dem Server bleibt. */
    suspend fun remove(serverId: String) {
        bookDao.deleteServer(serverId)
        secretStore.remove(tokenKey(serverId))
        secretStore.remove(AbsSecrets.refresh(serverId))
        secretStore.remove(AbsSecrets.login(serverId))
        registry.unregister(serverId)
        renewal.forget(serverId)
    }

    // --- Uhr (Wear-Konzept 16.2): Server ohne Schlüssel gespiegelt, der Schlüssel einzeln auf Anfrage ---

    /**
     * Handy: der Schlüssel für die anfragende Uhr; `null`, wenn [serverId] kein Audiobookshelf-Server ist. Bei OpenID ein
     * Access-Token, der noch mindestens eine halbe Stunde gilt; den Refresh-Token bekommt die Uhr nie, sonst sperrten sich beide
     * mit der Rotation gegenseitig aus (Konzept 12.11).
     */
    suspend fun apiKey(serverId: String): String? {
        val entity = bookDao.server(serverId) ?: return null
        val token = freshToken(serverId) ?: return secretStore.get(tokenKey(serverId))
        if (!entity.openId) return token
        // Geht die Uhr des Handys nach, hielte es einen abgelaufenen Token für gültig: Der Server entscheidet (bei 401 erneuert
        // der AbsTokenAuthenticator, die Registry hat danach den neuen).
        try {
            registry[serverId]?.let { api.me(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Token für die Uhr nicht geprüft", e)
        }
        return registry[serverId]?.token ?: token
    }

    /**
     * Uhr: eingeschaltete Server, deren Access-Token in weniger als [withinSeconds] abläuft (nur bei OpenID; API-Keys laufen
     * nicht ab). Die Uhr fragt dafür vor einem Download-Lauf beim Handy einen frischen an.
     */
    suspend fun withExpiringToken(withinSeconds: Long): List<String> {
        val deadline = System.currentTimeMillis() / 1000 + withinSeconds
        return all().filter { server -> AbsAuthApi.expiresAt(server.token)?.let { it < deadline } == true }.map { it.serverId }
    }

    /**
     * Uhr: übernimmt die Server des Handys. Fehlt einer dort, verschwindet er samt seinen Büchern und dem Schlüssel; die
     * übrigen behalten ihre Bücher (gleiche `serverId`).
     * @return die Server, deren Schlüssel verworfen ist, weil er sich am Handy geändert hat oder zum alten Host gehört: Die
     * Uhr fragt ihn gleich neu an.
     */
    suspend fun mirror(servers: List<MirroredAbsServer>): Set<String> {
        val existing = bookDao.servers().associateBy { it.serverId }
        val renewed = stamps.changedAbs(servers.associate { it.serverId to it.credentialsStamp })
            .filterTo(HashSet()) { it in existing }
        renewed.forEach { forgetKey(it) }
        servers.forEach { server ->
            val entity = (existing[server.serverId] ?: SpokenServerEntity(server.serverId, server.name, server.baseUrl))
                .copy(name = server.name, baseUrl = server.baseUrl)
            if (entity != existing[server.serverId]) bookDao.upsertServer(entity)
            val previousUrl = existing[server.serverId]?.baseUrl
            if (previousUrl != null && entity.baseUrl != previousUrl) {
                val oldUrl = previousUrl.toHttpUrlOrNull()
                if (oldUrl != null && entity.baseUrl.toHttpUrlOrNull()?.acceptsCredentialsOf(oldUrl) == true) {
                    // Neue Adresse auf demselben Host: mit dem gespeicherten Schlüssel neu eintragen.
                    secretStore.get(tokenKey(server.serverId))?.let { key -> register(server.serverId, server.baseUrl, key) }
                } else {
                    // Anderer Host: Der Schlüssel bleibt beim alten (die Uhr fragt ihn beim Handy neu an).
                    forgetKey(server.serverId)
                    renewed += server.serverId
                }
            }
        }
        (existing.keys - servers.mapTo(HashSet()) { it.serverId }).forEach {
            remove(it)
            stamps.forgetAbs(it)
        }
        return renewed
    }

    private suspend fun forgetKey(serverId: String) {
        secretStore.remove(tokenKey(serverId))
        registry.unregister(serverId)
    }

    /** Uhr: @return `false`, wenn der Server (noch) nicht gespiegelt ist. */
    suspend fun storeKey(serverId: String, key: String): Boolean {
        val entity = bookDao.server(serverId) ?: return false
        secretStore.put(tokenKey(serverId), key)
        register(serverId, entity.baseUrl, key)
        return true
    }

    private suspend fun register(serverId: String, baseUrl: String, key: String) {
        ensureLoaded()
        AbsServer.normalizeBaseUrl(baseUrl)?.let { registry.register(AbsServer(serverId, it, key)) }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val loadedServers = bookDao.servers().mapNotNull { entity ->
                val key = secretStore.get(tokenKey(entity.serverId))
                val baseUrl = AbsServer.normalizeBaseUrl(entity.baseUrl)
                if (key == null || baseUrl == null) {
                    Log.w(TAG, "Server ${entity.name} ist unvollständig eingerichtet und wird übersprungen")
                    null
                } else {
                    AbsServer(entity.serverId, baseUrl, key)
                }
            }
            registry.replaceAll(loadedServers)
            loaded = true
        }
    }

    private companion object {
        const val TAG = "AudiobookshelfServers"
        const val FRESH_FOR_SECONDS = 30 * 60L

        fun tokenKey(serverId: String) = AbsSecrets.token(serverId)
    }
}
