package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Benutzername und Passwort eines geschützten Feeds (HTTP Basic, Konzept 12.10 „Geschützte Feeds“). Sie gelten nur für
 * Adressen auf dem Host von [scope] (der Feed-Adresse, unter der sie eingegeben wurden; [acceptsCredentialsOf]): Zieht ein
 * Abo auf einen anderen Host um, bleiben sie dort, wo der Nutzer sie hingeschickt hat.
 */
data class FeedAuth(val scope: HttpUrl, val username: String, val password: String) {
    override fun toString() = "FeedAuth(${scope.host}, $username)"

    fun appliesTo(url: HttpUrl): Boolean = url.acceptsCredentialsOf(scope)

    internal val header: String get() = Credentials.basic(username, password, Charsets.UTF_8)
}

/**
 * Zugangsdaten aller Abos, damit [FeedAuthInterceptor] auch Anfragen anmeldet, die nicht vom Feed-Abruf stammen
 * (Media3-Streams, Downloads, Bilder und Kapitellisten auf demselben Host).
 */
@Singleton
class FeedAuthRegistry @Inject constructor() {

    @Volatile
    private var byFeed: Map<String, FeedAuth> = emptyMap()

    fun replaceAll(auth: Map<String, FeedAuth>) {
        byFeed = auth
    }

    /**
     * Die Zugangsdaten für [url]. Liegen mehrere Feeds mit verschiedenen Konten auf demselben Host, passt der, dessen Adresse
     * der von [url] im Pfad am längsten gleicht (Medien und Bilder liegen meist neben dem Feed); sonst der erste.
     */
    fun forUrl(url: HttpUrl): FeedAuth? =
        byFeed.values.filter { it.appliesTo(url) }.maxByOrNull { sharedDirectories(it.scope, url) }

    // Ohne die letzte Stelle: Sie ist die Datei, nicht der Ordner.
    private fun sharedDirectories(a: HttpUrl, b: HttpUrl): Int =
        a.pathSegments.dropLast(1).zip(b.pathSegments.dropLast(1)).takeWhile { (x, y) -> x == y }.size
}

/**
 * Setzt `Authorization: Basic …` für Anfragen an den Host eines geschützten Feeds. Die Zugangsdaten kommen aus dem
 * Request-Tag [FeedAuth] (Feed-Abruf; auch beim Hinzufügen, wenn sie noch nicht gespeichert sind) oder aus der
 * [FeedAuthRegistry]. Nach einer Weiterleitung auf einen anderen Host oder von HTTPS auf HTTP gehen sie nicht mit.
 * Als *Network*-Interceptor wie die übrigen Anmeldungen: Der Header landet nicht in Cache-Schlüsseln, und eine schon
 * gesetzte Anmeldung (Audiobookshelf, Jellyfin auf demselben Host) bleibt unangetastet.
 */
class FeedAuthInterceptor @Inject constructor(
    private val registry: FeedAuthRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val auth = original.tag(FeedAuth::class.java)?.takeIf { it.appliesTo(original.url) }
            ?: registry.forUrl(original.url)
            ?: return chain.proceed(original)
        val authorized = original.newBuilder().header(AUTHORIZATION, auth.header).build()
        return chain.proceed(authorized).newBuilder().request(original).build()
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * Trägt eine eingegebene Feed-Adresse Zugangsdaten (`https://name:passwort@host/feed`), werden sie herausgelöst: OkHttp
 * schickt sie von selbst nicht, und gespeichert, exportiert oder geteilt werden soll nur die Adresse ohne sie.
 */
fun HttpUrl.splitCredentials(): Pair<HttpUrl, FeedAuth?> {
    if (username.isEmpty()) return this to null
    val bare = newBuilder().username("").password("").build()
    return bare to FeedAuth(bare, username, password)
}
