package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * Stempel der Zugangsdaten eines Servers ([MirroredServer.credentialsStamp], bei Audiobookshelf
 * `MirroredAbsServer.credentialsStamp`): Das Handy berechnet ihn ([stamp], [absStamp]), die Uhr verwirft damit Zugangsdaten,
 * die am Handy inzwischen andere sind ([renewChanged], [changedAbs]), statt erst an einer Ablehnung des Servers zu merken,
 * dass ihre veraltet sind (neues Passwort, neu angemeldet, neuer API-Schlüssel).
 */
class CredentialsStamps @Inject constructor(private val secretStore: SecretStore) {

    /**
     * Handy: HMAC über Secret und Gerät mit einem Schlüssel, der nur auf dem Handy liegt. Ändert sich mit den Zugangsdaten, lässt
     * aber keinen Rückschluss auf ein (schwaches) Passwort zu. `null` ohne Zugangsdaten.
     */
    suspend fun stamp(sourceId: String): String? {
        val secret = secretStore.get(DataSourceRegistry.passwordKey(sourceId)) ?: return null
        val deviceId = secretStore.get(DataSourceRegistry.deviceIdKey(sourceId)).orEmpty()
        return hmac("$sourceId\n$secret\n$deviceId")
    }

    /** Handy: wie [stamp] für den API-Schlüssel [apiKey] eines Audiobookshelf-Servers. */
    suspend fun absStamp(serverId: String, apiKey: String?): String? = apiKey?.let { hmac("abs\n$serverId\n$it") }

    /**
     * Uhr: verwirft die Zugangsdaten der Server, deren Stempel sich geändert hat, und merkt sich die neuen. Ohne gemerkten Stempel
     * (erste Übernahme) bleiben sie. @return die Server mit verworfenen Zugangsdaten.
     */
    suspend fun renewChanged(servers: List<MirroredServer>): Set<String> {
        val renewed = changed(servers.associate { it.sourceId to it.credentialsStamp }, ::stampKey)
        renewed.forEach { sourceId ->
            secretStore.remove(DataSourceRegistry.passwordKey(sourceId))
            secretStore.remove(DataSourceRegistry.deviceIdKey(sourceId))
        }
        return renewed
    }

    /**
     * Uhr, Audiobookshelf: merkt sich die Stempel ([stamps] je Server) wie [renewChanged]; den Schlüssel verwirft der Aufrufer.
     * @return die Server, deren Stempel sich geändert hat.
     */
    suspend fun changedAbs(stamps: Map<String, String?>): Set<String> = changed(stamps, ::absStampKey)

    /** Uhr: Der Server ist weg. */
    suspend fun forget(sourceId: String) = secretStore.remove(stampKey(sourceId))

    /** Uhr: Der Audiobookshelf-Server ist weg. */
    suspend fun forgetAbs(serverId: String) = secretStore.remove(absStampKey(serverId))

    private suspend fun changed(stamps: Map<String, String?>, stampKey: (String) -> String): Set<String> {
        val changed = HashSet<String>()
        stamps.forEach { (id, stamp) ->
            if (stamp == null) return@forEach
            val key = stampKey(id)
            val known = secretStore.get(key)
            if (known == stamp) return@forEach
            if (known != null) changed += id
            secretStore.put(key, stamp)
        }
        return changed
    }

    private suspend fun hmac(input: String): String {
        val key = secretStore.get(KEY) ?: ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes).toHex().also { secretStore.put(KEY, it) }
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key.toByteArray(), HMAC)) }
        return mac.doFinal(input.toByteArray()).copyOf(STAMP_BYTES).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val KEY = "wear:credentials_stamp_key"
        const val KEY_BYTES = 32
        const val STAMP_BYTES = 8

        fun stampKey(sourceId: String) = "server:$sourceId:stamp"

        fun absStampKey(serverId: String) = "abs:$serverId:stamp"
    }
}
