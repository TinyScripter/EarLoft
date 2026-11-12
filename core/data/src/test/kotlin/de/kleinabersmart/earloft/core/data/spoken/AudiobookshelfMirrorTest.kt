package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Audiobookshelf-Server zwischen Handy und Uhr (Wear-Konzept 16.2): Stempel des Schlüssels und Spiegeln. */
@RunWith(RobolectricTestRunner::class)
class AudiobookshelfMirrorTest {

    private class MemorySecrets : SecretStore {
        val values = HashMap<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private lateinit var db: EarLoftDatabase
    private val secrets = MemorySecrets()
    private val registry = AbsServerRegistry()
    private lateinit var servers: AudiobookshelfServers

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        servers = testAudiobookshelfServers(db.bookDao(), secrets, AbsApi(OkHttpClient()), registry)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the phone stamps the key of enabled servers`() = runTest {
        db.bookDao().upsertServer(SpokenServerEntity("abs", "Hörbücher", "https://abs.example.org/"))
        db.bookDao().upsertServer(SpokenServerEntity("off", "Aus", "https://off.example.org/", enabled = false))
        assertNull(servers.mirrored.first().single().credentialsStamp)

        secrets.values["abs:abs:token"] = "key1"
        val first = servers.mirrored.first().single().credentialsStamp
        assertNotNull(first)
        assertEquals(first, servers.mirrored.first().single().credentialsStamp)
        secrets.values["abs:abs:token"] = "key2"
        assertNotEquals(first, servers.mirrored.first().single().credentialsStamp)
    }

    @Test
    fun `the watch drops a key whose stamp changed on the phone`() = runTest {
        assertEquals(emptySet<String>(), servers.mirror(listOf(server("s1"))))
        servers.storeKey("abs", "key1")
        assertNotNull(registry["abs"])

        assertEquals(emptySet<String>(), servers.mirror(listOf(server("s1"))))
        assertEquals("key1", secrets.values["abs:abs:token"])

        assertEquals(setOf("abs"), servers.mirror(listOf(server("s2"))))
        assertNull(secrets.values["abs:abs:token"])
        assertNull(registry["abs"])
        assertEquals(listOf("abs"), servers.withoutKey())

        // Ohne Stempel (Handy ohne Schlüssel, ältere App) bleibt der neue Schlüssel.
        servers.storeKey("abs", "key2")
        assertEquals(emptySet<String>(), servers.mirror(listOf(server(null))))
        assertEquals("key2", secrets.values["abs:abs:token"])
    }

    @Test
    fun `the watch asks again for a key that belonged to another host`() = runTest {
        servers.mirror(listOf(server("s1")))
        servers.storeKey("abs", "key1")

        assertEquals(setOf("abs"), servers.mirror(listOf(server("s1", baseUrl = "https://andere.example.org/"))))
        assertNull(secrets.values["abs:abs:token"])

        servers.mirror(emptyList())
        assertNull(secrets.values["abs:abs:stamp"])
    }

    private fun server(stamp: String?, baseUrl: String = "https://abs.example.org/") =
        MirroredAbsServer("abs", "Hörbücher", baseUrl, stamp)
}
