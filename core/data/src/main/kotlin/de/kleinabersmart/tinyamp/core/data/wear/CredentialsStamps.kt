package de.kleinabersmart.tinyamp.core.data.wear

import de.kleinabersmart.tinyamp.core.data.security.SecretStore
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * Stempel der Zugangsdaten eines Servers ([MirroredServer.credentialsStamp]): Das Handy berechnet ihn ([stamp]), die Uhr verwirft
 * damit Zugangsdaten, die am Handy inzwischen andere sind ([renewChanged]), statt erst an einer Ablehnung des Servers zu merken,
 * dass ihre veraltet sind (neues Passwort, neu angemeldet).
 */
class CredentialsStamps @Inject constructor(private val secretStore: SecretStore) {

    /**
     * Handy: HMAC über Secret und Gerät mit einem Schlüssel, der nur auf dem Handy liegt. Ändert sich mit den Zugangsdaten, lässt
     * aber keinen Rückschluss auf ein (schwaches) Passwort zu. `null` ohne Zugangsdaten.
     */
    suspend fun stamp(sourceId: String): String? {
        val secret = secretStore.get(DataSourceRegistry.passwordKey(sourceId)) ?: return null
        val key = secretStore.get(KEY) ?: ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes).toHex().also { secretStore.put(KEY, it) }
        val deviceId = secretStore.get(DataSourceRegistry.deviceIdKey(sourceId)).orEmpty()
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key.toByteArray(), HMAC)) }
        return mac.doFinal("$sourceId\n$secret\n$deviceId".toByteArray()).copyOf(STAMP_BYTES).toHex()
    }

    /**
     * Uhr: verwirft die Zugangsdaten der Server, deren Stempel sich geändert hat, und merkt sich die neuen. Ohne gemerkten Stempel
     * (erste Übernahme) bleiben sie. @return die Server mit verworfenen Zugangsdaten.
     */
    suspend fun renewChanged(servers: List<MirroredServer>): Set<String> {
        val renewed = HashSet<String>()
        servers.forEach { server ->
            val stamp = server.credentialsStamp ?: return@forEach
            val key = stampKey(server.sourceId)
            val known = secretStore.get(key)
            if (known == stamp) return@forEach
            if (known != null) {
                secretStore.remove(DataSourceRegistry.passwordKey(server.sourceId))
                secretStore.remove(DataSourceRegistry.deviceIdKey(server.sourceId))
                renewed += server.sourceId
            }
            secretStore.put(key, stamp)
        }
        return renewed
    }

    /** Uhr: Der Server ist weg. */
    suspend fun forget(sourceId: String) = secretStore.remove(stampKey(sourceId))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val KEY = "wear:credentials_stamp_key"
        const val KEY_BYTES = 32
        const val STAMP_BYTES = 8

        fun stampKey(sourceId: String) = "server:$sourceId:stamp"
    }
}
