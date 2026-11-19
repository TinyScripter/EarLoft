package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialsStampsTest {

    private class MemorySecrets : SecretStore {
        val values = HashMap<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private val phoneSecrets = MemorySecrets()
    private val phone = CredentialsStamps(phoneSecrets)
    private val watchSecrets = MemorySecrets()
    private val watch = CredentialsStamps(watchSecrets)

    @Test
    fun `the stamp changes with the secret and the device but does not contain them`() = runTest {
        assertNull(phone.stamp("jf"))
        phoneSecrets.values[DataSourceRegistry.passwordKey("jf")] = "user:token1"
        val first = phone.stamp("jf")!!

        assertEquals(first, phone.stamp("jf"))
        assertFalse("token1" in first)
        phoneSecrets.values[DataSourceRegistry.deviceIdKey("jf")] = "earloft-x"
        val withDevice = phone.stamp("jf")
        assertNotEquals(first, withDevice)
        phoneSecrets.values[DataSourceRegistry.passwordKey("jf")] = "user:token2"
        assertNotEquals(withDevice, phone.stamp("jf"))
    }

    @Test
    fun `the watch drops credentials only when a known stamp changes`() = runTest {
        watchSecrets.values[DataSourceRegistry.passwordKey("jf")] = "user:token1"

        // First takeover: no remembered stamp, the credentials stay.
        assertEquals(emptySet<String>(), watch.renewChanged(listOf(server("a"))))
        assertEquals("user:token1", watchSecrets.values[DataSourceRegistry.passwordKey("jf")])
        assertEquals(emptySet<String>(), watch.renewChanged(listOf(server("a"))))

        watchSecrets.values[DataSourceRegistry.deviceIdKey("jf")] = "earloft-x"
        assertEquals(setOf("jf"), watch.renewChanged(listOf(server("b"))))
        assertNull(watchSecrets.values[DataSourceRegistry.passwordKey("jf")])
        assertNull(watchSecrets.values[DataSourceRegistry.deviceIdKey("jf")])
        // Without a stamp (phone has no credentials) everything stays as it is.
        assertEquals(emptySet<String>(), watch.renewChanged(listOf(server(null))))
    }

    private fun server(stamp: String?) =
        MirroredServer("jf", SourceType.JELLYFIN, "Jellyfin", "https://jf.example/", "anna", 0, formPost = false, credentialsStamp = stamp)
}
