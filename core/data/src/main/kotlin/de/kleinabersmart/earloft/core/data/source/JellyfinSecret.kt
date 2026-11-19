package de.kleinabersmart.earloft.core.data.source

/**
 * What is stored for a Jellyfin server in the [de.kleinabersmart.earloft.core.data.security.SecretStore]
 * (under [DataSourceRegistry.passwordKey]): user ID and token of the login – never the password (concept 14).
 */
data class JellyfinSecret(val userId: String, val token: String) {
    override fun toString() = "JellyfinSecret($userId)"

    fun encode(): String = "$userId:$token"

    companion object {
        fun decode(value: String): JellyfinSecret? {
            val parts = value.split(':', limit = 2)
            if (parts.size != 2 || parts.any { it.isBlank() }) return null
            return JellyfinSecret(parts[0], parts[1])
        }
    }
}
