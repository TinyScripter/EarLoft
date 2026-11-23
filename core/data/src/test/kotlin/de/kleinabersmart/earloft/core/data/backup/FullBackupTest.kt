package de.kleinabersmart.earloft.core.data.backup

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.SPOKEN_SETTINGS_MERGES
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.datasource.jellyfin.JellyfinDataSourceFactory
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import de.kleinabersmart.earloft.core.datasource.local.LocalDataSource
import de.kleinabersmart.earloft.core.datasource.subsonic.SubsonicDataSourceFactory
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.WearSelectionKind
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
import java.io.IOException
import java.io.InputStream
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

    /** A device with its own main and user database and its own settings. */
    private inner class Device(scope: CoroutineScope) {
        val main = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build().also(opened::add)
        val user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build().also(opened::add)
        /** Where the settings live. */
        val dir: File = folder.newFolder()
        val theme = store(scope, "theme_settings")
        val library = store(scope, "library_settings")
        val downloads = store(scope, "download_settings")
        val queue = store(scope, "episode_queue")
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
        private val notice = RestoreNotice(context, { registry }, { error("not needed") })
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
            ardBooks = { error("not needed") },
            scope = scope,
        ).also { it.start() }
        val backup = FullBackup(
            context,
            userData,
            SettingsBackup(
                context,
                setOf(
                    BackedUpSettings("theme_settings", theme),
                    BackedUpSettings("library_settings", library, setOf(LOCAL_MEDIA.name)),
                    BackedUpSettings("download_settings", downloads, merged = mapOf(AUTO_SERVERS.name to SettingsMerge.UNION_IDS)),
                    BackedUpSettings("episode_queue", queue, merged = EpisodeQueue.BACKUP_MERGES),
                ),
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

    private suspend fun EarLoftDatabase.addAbs(serverId: String = "abs", libraryId: String = "lib") =
        bookDao().upsertServer(SpokenServerEntity(serverId, "ABS", "https://abs.example", libraryId = libraryId))

    private suspend fun EarLoftDatabase.addFeed(feedId: String = "feed-1") =
        podcastDao().upsertFeed(PodcastFeedEntity(feedId, "https://example.org/feed", "Logbook", notifyNew = false))

    private suspend fun EarLoftDatabase.addEpisode(feedId: String, guid: String): Long = bookDao().upsertBook(
        BookEntity(serverId = feedId, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "", durationMs = 1_000_000),
    )

    /** An episode with its file, as the sync creates it. */
    private suspend fun EarLoftDatabase.addEpisodeWithTrack(feedId: String, guid: String, addedAt: Long, state: TrackDownload = TrackDownload.NONE): Long {
        val id = bookDao().upsertBook(
            BookEntity(
                serverId = feedId, kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "",
                durationMs = 1_000_000, addedAt = addedAt,
            ),
        )
        bookDao().insertTracks(listOf(BookTrackEntity(id, 0, "https://example.org/$guid.mp3", 0, 1_000_000, downloadState = state)))
        return id
    }

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
                groupTitle = "Logbook", itemKey = "guid-1", itemTitle = "Episode 1", listenedMs = 60_000, lastListenedAt = 1_000,
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
        assertEquals(4, result.settings)
        assertFalse(result.localSourcesSkipped)
        assertEquals(listOf("nav"), new.main.sourceDao().getServers().map { it.sourceId })
        assertEquals(listOf("abs"), new.main.bookDao().servers().map { it.serverId })
        assertFalse(new.main.podcastDao().feed("feed-1")!!.notifyNew)
        assertEquals(60_000L, new.main.statsDao().all().single().listenedMs)
        // Without password: the settings show "Re-enter password".
        assertEquals(listOf("nav"), new.registry.withoutPassword())
        // The episode only exists after the sync; until then its progress waits in `user.db`.
        assertEquals(4_000L, new.user.userDataDao().progress().single().positionMs)
        // Settings replaced: whatever the backup doesn't know is dropped.
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
        old.downloads.edit { it[AUTO_SERVERS] = setOf("nav") }
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()

        val new = Device(backgroundScope)
        new.main.addNavidrome("nav-here")
        new.main.addAbs("abs-here")
        new.main.addFeed("feed-here")
        val episode = new.main.addEpisode("feed-here", "guid-1")
        val result = new.backup.import(ByteArrayInputStream(file))

        assertEquals(BackupCounts(), result.added)
        assertEquals(setOf("nav-here"), new.downloads.data.first()[AUTO_SERVERS])
        assertEquals(listOf("nav-here"), new.main.sourceDao().getServers().map { it.sourceId })
        assertEquals(listOf("abs-here"), new.main.bookDao().servers().map { it.serverId })
        assertEquals(listOf("feed-here"), new.main.podcastDao().feeds().map { it.feedId })
        // The progress is tied to the local identifier of the subscription.
        assertEquals(4_000L, new.main.bookDao().progressOf(listOf(episode)).single().positionMs)
    }

    @Test
    fun `the queue and other user data in the settings are merged instead of replaced`() = runTest {
        val old = Device(backgroundScope)
        old.main.addFeed("feed-1")
        old.queue.edit { it[ORDER] = listOf("feed-1\u001Fguid-1", "feed-1\u001Fguid-2").joinToString("\u001E") }
        old.downloads.edit { it[AUTO_SERVERS] = setOf("nav-old") }
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()

        val new = Device(backgroundScope)
        new.main.addFeed("feed-here")
        new.queue.edit { it[ORDER] = listOf("feed-here\u001Fguid-2", "feed-here\u001Fguid-9").joinToString("\u001E") }
        new.downloads.edit { it[AUTO_SERVERS] = setOf("nav-here") }
        new.backup.import(ByteArrayInputStream(file))

        // The local order stays in front; from the backup only what's missing is added, under the local identifier of the subscription.
        assertEquals(
            listOf("feed-here\u001Fguid-2", "feed-here\u001Fguid-9", "feed-here\u001Fguid-1"),
            new.queue.data.first()[ORDER]!!.split("\u001E"),
        )
        assertEquals(setOf("nav-here", "nav-old"), new.downloads.data.first()[AUTO_SERVERS])
    }

    @Test
    fun `an audiobookshelf server is recognized by its address even when its first library differs`() = runTest {
        val old = Device(backgroundScope)
        old.main.addAbs("abs", libraryId = "lib-books")
        val book = old.main.bookDao().upsertBook(
            BookEntity(serverId = "abs", kind = BookKind.BOOK, remoteId = "li_1", title = "Thriller", normTitle = "thriller", author = "", durationMs = 1_000_000),
        )
        // Not yet reported: the app only backs up such progress (the server has the rest).
        old.main.bookDao().upsertProgress(BookProgressEntity(book, positionMs = 7_000, durationMs = 1_000_000, updatedAt = 10, dirty = true))
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()

        // Here a different library is the first one shown (deselected or reordered in Audiobookshelf).
        val new = Device(backgroundScope)
        new.main.addAbs("abs-here", libraryId = "lib-plays")
        val here = new.main.bookDao().upsertBook(
            BookEntity(serverId = "abs-here", kind = BookKind.BOOK, remoteId = "li_1", title = "Thriller", normTitle = "thriller", author = "", durationMs = 1_000_000),
        )
        val result = new.backup.import(ByteArrayInputStream(file))

        assertEquals(BackupCounts(), result.added)
        assertEquals(listOf("abs-here"), new.main.bookDao().servers().map { it.serverId })
        assertEquals(7_000L, new.main.bookDao().progressOf(listOf(here)).single().positionMs)
    }

    @Test
    fun `book statistics and the watch's server playlists move to the server ids known here`() = runTest {
        val old = Device(backgroundScope)
        old.main.addNavidrome("nav")
        old.main.addAbs("abs")
        old.main.statsDao().addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP, kind = BookKind.BOOK, groupKey = "abs", groupTitle = "ABS",
                itemKey = "li_1", itemTitle = "Thriller", listenedMs = 60_000, lastListenedAt = 1_000,
            ),
            day = 20_000,
        )
        val playlist = old.main.playlistDao().insertPlaylist(PlaylistEntity(name = "Mix", boundSourceId = "nav", remotePlaylistId = "p1", updatedAt = 1))
        old.main.wearDao().upsert(WearSelectionEntity(WearSelectionKind.PLAYLIST.key(playlist), WearSelectionKind.PLAYLIST, playlist, addedAt = 8))
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()

        val new = Device(backgroundScope)
        new.main.addNavidrome("nav-here")
        new.main.addAbs("abs-here")
        val here = new.main.playlistDao().insertPlaylist(PlaylistEntity(name = "Mix", boundSourceId = "nav-here", remotePlaylistId = "p1", updatedAt = 1))
        new.backup.import(ByteArrayInputStream(file))

        assertEquals(listOf("abs-here"), new.main.statsDao().all().map { it.groupKey })
        assertEquals(listOf("abs-here"), new.main.statsDao().allDays().map { it.groupKey })
        assertEquals(listOf(here), new.main.wearDao().getSelections().map { it.refId })
    }

    @Test
    fun `a restored subscription downloads only what the old device had, then new episodes again`() = runTest {
        val old = Device(backgroundScope)
        old.main.addFeed()
        old.main.bookDao().upsertProgress(
            BookProgressEntity(old.main.addEpisodeWithTrack("feed-1", "heard", addedAt = 1), 1_000_000, 1_000_000, finished = true, updatedAt = 10),
        )
        old.main.addEpisodeWithTrack("feed-1", "removed", addedAt = 4, state = TrackDownload.REMOVED)
        old.main.addEpisodeWithTrack("feed-1", "loaded", addedAt = 3, state = TrackDownload.DONE)
        old.main.addEpisodeWithTrack("feed-1", "never", addedAt = 2)
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()

        val new = Device(backgroundScope)
        new.backup.import(ByteArrayInputStream(file))
        // The first sync creates the episodes in one go; the automation consults the backup beforehand
        // (`EpisodeDownloads.queueAutomatic`).
        val ids = new.main.withTransaction {
            listOf("heard" to 1L, "removed" to 4L, "loaded" to 3L, "never" to 2L)
                .associate { (guid, addedAt) -> guid to new.main.addEpisodeWithTrack("feed-1", guid, addedAt) }
        }
        new.userData.applyWaiting()
        val dao = new.main.podcastDao()
        assertEquals(0, dao.queueLatestUnheard("feed-1", 3))

        suspend fun state(guid: String) = dao.trackOf(ids.getValue(guid))!!.downloadState
        assertTrue(new.main.bookDao().progressOf(listOf(ids.getValue("heard"))).single().finished)
        assertEquals("loaded on the old device", TrackDownload.QUEUED, state("loaded"))
        for (guid in listOf("heard", "removed", "never")) assertEquals(guid, TrackDownload.REMOVED, state(guid))

        // An episode published afterwards is downloaded by the automation again.
        val newer = new.main.withTransaction { new.main.addEpisodeWithTrack("feed-1", "newer", addedAt = 5) }
        new.userData.applyWaiting()
        assertEquals(1, dao.queueLatestUnheard("feed-1", 3))
        assertEquals(TrackDownload.QUEUED, dao.trackOf(newer)!!.downloadState)
    }

    @Test
    fun `removed downloads are only kept as far as the automatic could still load them`() = runTest {
        val device = Device(backgroundScope)
        device.main.addFeed()
        // As after a restore: all episodes removed; one downloaded, the newest one played at addedAt 5.
        for (addedAt in 1L..30L) {
            val state = if (addedAt == 2L) TrackDownload.DONE else TrackDownload.REMOVED
            val id = device.main.addEpisodeWithTrack("feed-1", "e$addedAt", addedAt, state)
            if (addedAt == 5L) device.main.bookDao().upsertProgress(BookProgressEntity(id, 1_000_000, 1_000_000, finished = true, updatedAt = 1))
        }

        val saved = device.main.podcastDao().keyedEpisodeDownloads()

        assertEquals(listOf("e2"), saved.filter { it.downloadState == TrackDownload.DONE }.map { it.remoteId })
        assertEquals((11..30).map { "e$it" }.toSet(), saved.filter { it.downloadState == TrackDownload.REMOVED }.map { it.remoteId }.toSet())
    }

    @Test
    fun `files that are no backup or come from a newer version are refused`() = runTest {
        val device = Device(backgroundScope)
        try {
            device.backup.import(ByteArrayInputStream("no zip".toByteArray()))
            fail("non-backup accepted")
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
            fail("newer backup accepted")
        } catch (e: FullBackupException) {
            assertTrue(e.newerVersion)
        }
    }

    @Test
    fun `a file that breaks off while reading is unreadable, not a foreign file`() = runTest {
        val old = Device(backgroundScope)
        old.main.addNavidrome()
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()
        // A cloud file whose download stops halfway.
        val breaking = object : InputStream() {
            private val data = ByteArrayInputStream(file, 0, file.size / 2)
            override fun read(): Int = data.read().takeIf { it >= 0 } ?: throw IOException("connection lost")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                data.read(b, off, len).takeIf { it >= 0 } ?: throw IOException("connection lost")
        }
        val fresh = Device(backgroundScope)
        try {
            fresh.backup.import(breaking)
            fail("broken file restored")
        } catch (_: FullBackupException) {
            fail("read error reported as a foreign file")
        } catch (_: FullBackupInterruptedException) {
            fail("read error reported as interrupted")
        } catch (_: IOException) {
            // The view model reports this as unreadable.
        }
        assertTrue(fresh.main.sourceDao().getServers().isEmpty())
    }

    @Test
    fun `a restore that fails after it began says so, and what it restored stays`() = runTest {
        val old = Device(backgroundScope)
        old.main.addNavidrome()
        old.theme.edit { it[MODE] = "DARK" }
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()
        val fresh = Device(backgroundScope)
        // The settings can't be written (storage full): the user data before them is already in.
        fresh.dir.setWritable(false)
        try {
            fresh.backup.import(ByteArrayInputStream(file))
            fail("failed settings not reported")
        } catch (_: FullBackupInterruptedException) {
        } finally {
            fresh.dir.setWritable(true)
        }
        assertEquals(listOf("nav"), fresh.main.sourceDao().getServers().map { it.sourceId })
        // Restoring again adds the rest and creates nothing twice.
        fresh.backup.import(ByteArrayInputStream(file))
        assertEquals(listOf("nav"), fresh.main.sourceDao().getServers().map { it.sourceId })
        assertEquals("DARK", fresh.theme.data.first()[MODE])
    }

    @Test
    fun `an entry that unpacks to far more than a backup holds is refused before it is in memory`() = runTest {
        val device = Device(backgroundScope)
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zip ->
            // A few kilobytes in the file, 8 MB unpacked: far beyond any real manifest.
            zip.putNextEntry(ZipEntry("earloft-backup.json"))
            zip.write("{\"format\":1,\"padding\":\"".toByteArray())
            repeat(8) { zip.write(ByteArray(1024 * 1024) { 'x'.code.toByte() }) }
            zip.write("\"}".toByteArray())
        }
        assertTrue(bomb.size() < 64 * 1024)
        try {
            device.backup.import(ByteArrayInputStream(bomb.toByteArray()))
            fail("oversized manifest accepted")
        } catch (e: FullBackupException) {
            assertFalse(e.newerVersion)
        }
    }

    @Test
    fun `a value this version does not know is refused as a backup of a newer version`() = runTest {
        val old = Device(backgroundScope)
        old.main.addNavidrome()
        val file = ByteArrayOutputStream().also { old.backup.export(it) }.toByteArray()
        // Same schema, but a server type that only a newer version knows.
        val changed = ByteArrayOutputStream()
        ZipOutputStream(changed).use { out ->
            ZipInputStream(ByteArrayInputStream(file)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    var bytes = zip.readBytes()
                    if (entry.name == "user.db") {
                        val db = folder.newFile("changed.db").apply { writeBytes(bytes) }
                        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                            it.execSQL("UPDATE servers SET type = 'FUTURE'")
                        }
                        bytes = db.readBytes()
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                }
            }
        }

        val new = Device(backgroundScope)
        try {
            new.backup.import(ByteArrayInputStream(changed.toByteArray()))
            fail("unknown value accepted")
        } catch (e: FullBackupException) {
            assertTrue(e.newerVersion)
        }
        assertTrue(new.main.sourceDao().getServers().isEmpty())
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

    @Test
    fun `subscriptions and known books of ARD Sounds are merged, the notes of this device stay`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("spoken_settings.preferences_pb") }
        val settings = SettingsBackup(context, setOf(BackedUpSettings("spoken_settings", store, merged = SPOKEN_SETTINGS_MERGES)))
        val shows = stringSetPreferencesKey("ard_shows")
        val seen = stringSetPreferencesKey("ard_seen")
        val gone = stringSetPreferencesKey("ard_gone")
        store.edit {
            it[shows] = setOf("show:a\tThriller", "show:b\tRadio play old")
            it[seen] = setOf("show:a\tbook:1")
            it[gone] = setOf("book:9\t100")
        }
        val saved = settings.read()
        store.edit {
            it.clear()
            it[shows] = setOf("show:b\tRadio play", "show:c\tDocumentary")
            it[seen] = setOf("show:c\tbook:3")
            it[gone] = setOf("book:8\t200")
        }

        settings.write(saved)

        val now = store.data.first()
        assertEquals(setOf("show:a\tThriller", "show:b\tRadio play", "show:c\tDocumentary"), now[shows])
        assertEquals(setOf("show:a\tbook:1", "show:c\tbook:3"), now[seen])
        assertEquals(setOf("book:8\t200"), now[gone])
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val SYNC_DAYS = intPreferencesKey("sync_interval_days")
        val LOCAL_MEDIA = booleanPreferencesKey("use_local_media")
        val AUTO_SERVERS = stringSetPreferencesKey("auto_download_servers")
        val ORDER = stringPreferencesKey("order_keys")
    }
}
