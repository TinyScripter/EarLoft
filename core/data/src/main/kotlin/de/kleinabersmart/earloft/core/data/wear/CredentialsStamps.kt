package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * Stamp of a server's credentials ([MirroredServer.credentialsStamp], for Audiobookshelf
 * `MirroredAbsServer.credentialsStamp`): the phone computes it ([stamp], [absStamp]); the watch uses it to discard credentials
 * that have since changed on the phone ([renewChanged], [changedAbs]), instead of only noticing from a server rejection
 * that its own are outdated (new password, signed in again, new API key).
 */
class CredentialsStamps @Inject constructor(private val secretStore: SecretStore) {

    /**
     * Phone: HMAC over secret and device with a key that only lives on the phone. Changes with the credentials, but
     * allows no inference about a (weak) password. `null` without credentials.
     */
    suspend fun stamp(sourceId: String): String? {
        val secret = secretStore.get(DataSourceRegistry.passwordKey(sourceId)) ?: return null
        val deviceId = secretStore.get(DataSourceRegistry.deviceIdKey(sourceId)).orEmpty()
        return hmac("$sourceId\n$secret\n$deviceId")
    }

    /** Phone: like [stamp] for the API key [apiKey] of an Audiobookshelf server. */
    suspend fun absStamp(serverId: String, apiKey: String?): String? = apiKey?.let { hmac("abs\n$serverId\n$it") }

    /**
     * Watch: discards the credentials of the servers whose stamp has changed, and remembers the new ones. Without a remembered
     * stamp (first adoption) they stay. @return the servers with discarded credentials.
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
     * Watch, Audiobookshelf: remembers the stamps ([stamps] per server) like [renewChanged]; the caller discards the key.
     * @return the servers whose stamp has changed.
     */
    suspend fun changedAbs(stamps: Map<String, String?>): Set<String> = changed(stamps, ::absStampKey)

    /** Watch: the server is gone. */
    suspend fun forget(sourceId: String) = secretStore.remove(stampKey(sourceId))

    /** Watch: the Audiobookshelf server is gone. */
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
