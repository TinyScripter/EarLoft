package de.kleinabersmart.earloft.core.data.backup

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.datasource.jellyfin.JellyfinDataSourceFactory
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import de.kleinabersmart.earloft.core.datasource.local.LocalDataSource
import de.kleinabersmart.earloft.core.datasource.subsonic.SubsonicDataSourceFactory
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinClientInfo
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServerRegistry
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicApi
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class FullBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<RoomDatabase>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    /** Ein Gerät mit eigener Haupt- und Nutzerdatenbank und eigenen Einstellungen. */
    private inner class Device(scope: CoroutineScope) {
        val main = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build().also(opened::add)
        val user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().also(opened::add)
        private val dir = folder.newFolder()
        val theme = store(scope, "theme_settings")
        val library = store(scope, "library_settings")
        val registry = DataSourceRegistry(
            localDataSource = LocalDataSource(context),
            sourceDao = main.sourceDao(),
            secretStore = NoSecrets,
            subsonicFactory = SubsonicDataSourceFactory(context, SubsonicApi(OkHttpClient())),
            serverRegistry = SubsonicServerRegistry(),
            jellyfinFactory = JellyfinDataSourceFactory(context, JellyfinApi(OkHttpClient(), JellyfinClientInfo("Test", "1"))),
            jellyfinRegistry = JellyfinServerRegistry(),
            librarySettings = LibrarySettings(context),
        )
        private val notice = RestoreNotice(context, { registry }, { error("nicht gebraucht") })
        val userData = UserDataBackup(
            context = context,
            database = main,
            userDao = user.userDataDao(),
            sourceDao = main.sourceDao(),
            bookDao = main.bookDao(),
            podcastDao = main.podcastDao(),
            statsDao = main.statsDao(),
            music = MusicBackup(main.backupDao(), main.playlistDao(), main.syncDao()),
            restoreNotice = notice,
            registry = { registry },
            scope = scope,
        ).also { it.start() }
        val backup = FullBackup(
            context,
            userData,
            SettingsBackup(
                context,
                setOf(BackedUpSettings("theme_settings", theme), BackedUpSettings("library_settings", library, setOf(LOCAL_MEDIA.name))),
            ),
            notice,
        )

        private fun store(scope: CoroutineScope, name: String): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
    }

    private object NoSecrets : SecretStore {
        override suspend fun get(key: String): String? = null
        override suspend fun put(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }

    private suspend fun EarLoftDatabase.addNavidrome(sourceId: String = "nav") = sourceDao().upsert(
        SourceEntity(sourceId = sourceId, type = SourceType.SUBSONIC, name = "Navidrome", baseUrl = "https://nav.example", username = "sebi"),
    )

    private suspend fun EarLoftDatabase.addAbs(serverId: String = "abs") =
        bookDao().upsertServer(SpokenServerEntity(serverId, "ABS", "https://abs.example", libraryId = "lib"))

    private suspend fun EarLoftDatabase.addFeed(feedId: String = "feed-1") =
        podcastDao().upsertFeed(PodcastFeedEntity(feedId, "https://example.org/feed", "Logbuch", notifyNew = false))

    private suspend fun EarLoftDatabase.addEpisode(feedId: String, guid: String): Long = bookDao().upsertBook(
        BookEntity(serverId = feedId, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 1_000_000),
    )

    private fun entries(bytes: ByteArray): Set<String> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.map { it.name }.toSet()
    }

    @Test
    fun `export and import carry servers, subscriptions, statistics and settings, but no secrets`() = runTest {
        val old = Device(backgroundScope)
        old.main.addNavidrome()
        old.main.addAbs()
        old.main.addFeed()
        old.main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.EPISODE, groupKey = "https://example.org/feed",
                groupTitle = "Logbuch", itemKey = "guid-1", itemTitle = "Folge 1", listenedMs = 60_000, lastListenedAt = 1_000,
            ),
            day = 20_000,
        )
        old.main.bookDao().upsertProgress(
            BookProgressEntity(old.main.addEpisode("feed-1", "guid-1"), positionMs = 4_000, durationMs = 1_000_000, updatedAt = 10),
        )
        old.theme.edit { it[MODE] = "DARK" }
        old.library.edit {
            it[SYNC_DAYS] = 7
            it[LOCAL_MEDIA] = false
        }
        val file = ByteArrayOutputStream()
        val counts = old.backup.export(file)

        assertEquals(BackupCounts(musicServers = 1, audiobookServers = 1, podcasts = 1, playlists = 0), counts)
        assertEquals(setOf("earloft-backup.json", "user.db", "settings.json"), entries(file.toByteArray()))

        val new = Device(backgroundScope)
        new.theme.edit {
            it[MODE] = "LIGHT"
            it[booleanPreferencesKey("always_show_navigation_bar")] = false
        }
        val result = new.backup.import(ByteArrayInputStream(file.toByteArray()))

        assertEquals(BackupCounts(musicServers = 1, audiobookServers = 1, podcasts = 1, playlists = 0), result.added)
        assertEquals(2, result.settings)
        assertFalse(result.localSourcesSkipped)
        assertEquals(listOf("nav"), new.main.sourceDao().getServers().map { it.sourceId })
        assertEquals(listOf("abs"), new.main.bookDao().servers().map { it.serverId })
        assertFalse(new.main.podcastDao().feed("feed-1")!!.notifyNew)
        assertEquals(60_000L, new.main.statsDao().all().single().listenedMs)
        // Ohne Passwort: Die Einstellungen zeigen „Passwort neu eingeben“.
        assertEquals(listOf("nav"), new.registry.withoutPassword())
        // Die Folge gibt es erst nach dem Abgleich; bis dahin wartet ihr Fortschritt in `user.db`.
        assertEquals(4_000L, new.user.userDataDao().progress().single().positionMs)
        // Einstellungen ersetzt: Was die Sicherung nicht kennt, fällt weg.
        val theme = new.theme.data.first()
        assertEquals("DARK", theme[MODE])
        assertNull(theme[booleanPreferencesKey("always_show_navigation_bar")])
        assertEquals(7, new.library.data.first()[SYNC_DAYS])
    }

    @Test
    fun `import keeps what exists and maps servers and feeds known under another id`() = runTest {
        val old = Device(backgroundScope)
        old.main.addNavidrome("nav")
        old.main.addAbs("abs")
        old.main.addFeed("feed-1")
        old.main.bookDao().upsertProgress(
            BookProgressEntity(old.main.addEpisode("feed-1", "guid-1"), positionMs = 4_000, durationMs = 1_000_000, updatedAt = 10),
        )
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()

        val new = Device(backgroundScope)
        new.main.addNavidrome("nav-here")
        new.main.addAbs("abs-here")
        new.main.addFeed("feed-here")
        val episode = new.main.addEpisode("feed-here", "guid-1")
        val result = new.backup.import(ByteArrayInputStream(file))

        assertEquals(BackupCounts(), result.added)
        assertEquals(listOf("nav-here"), new.main.sourceDao().getServers().map { it.sourceId })
        assertEquals(listOf("abs-here"), new.main.bookDao().servers().map { it.serverId })
        assertEquals(listOf("feed-here"), new.main.podcastDao().feeds().map { it.feedId })
        // Der Fortschritt hängt an der hiesigen Kennung des Abos.
        assertEquals(4_000L, new.main.bookDao().progressOf(listOf(episode)).single().positionMs)
    }

    @Test
    fun `files that are no backup or come from a newer version are refused`() = runTest {
        val device = Device(backgroundScope)
        try {
            device.backup.import(ByteArrayInputStream("kein zip".toByteArray()))
            fail("keine Sicherung angenommen")
        } catch (e: FullBackupException) {
            assertFalse(e.newerVersion)
        }

        val newer = ByteArrayOutputStream()
        ZipOutputStream(newer).use { zip ->
            zip.putNextEntry(ZipEntry("earloft-backup.json"))
            zip.write(JSONObject().put("format", 1).put("userDatabaseVersion", UserDatabase.VERSION + 1).toString().toByteArray())
            zip.putNextEntry(ZipEntry("user.db"))
            zip.write(ByteArray(16))
        }
        try {
            device.backup.import(ByteArrayInputStream(newer.toByteArray()))
            fail("neuere Sicherung angenommen")
        } catch (e: FullBackupException) {
            assertTrue(e.newerVersion)
        }
    }

    @Test
    fun `sources on this device are only switched on with the permission to read audio files`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("library_settings.preferences_pb") }
        val settings = SettingsBackup(context, setOf(BackedUpSettings("library_settings", store, setOf(LOCAL_MEDIA.name))))
        store.edit {
            it[LOCAL_MEDIA] = true
            it[stringSetPreferencesKey("set")] = setOf("a", "b")
        }
        val saved = settings.read()
        store.edit {
            it.clear()
            it[LOCAL_MEDIA] = false
        }

        val denied = settings.write(saved)

        assertTrue(denied.localSourcesSkipped)
        assertEquals(false, store.data.first()[LOCAL_MEDIA])
        assertEquals(setOf("a", "b"), store.data.first()[stringSetPreferencesKey("set")])

        shadowOf(context as Application).grantPermissions(AudioPermission.name)
        val granted = settings.write(saved)

        assertFalse(granted.localSourcesSkipped)
        assertEquals(true, store.data.first()[LOCAL_MEDIA])
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val SYNC_DAYS = intPreferencesKey("sync_interval_days")
        val LOCAL_MEDIA = booleanPreferencesKey("use_local_media")
    }
}
