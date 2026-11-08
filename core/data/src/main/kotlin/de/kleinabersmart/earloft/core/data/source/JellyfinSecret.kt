package de.kleinabersmart.earloft.core.data.source

/**
 * Was von einem Jellyfin-Server im [de.kleinabersmart.earloft.core.data.security.SecretStore] liegt
 * (unter [DataSourceRegistry.passwordKey]): Benutzer-ID und Token der Anmeldung – nie das Passwort (Konzept 14).
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
