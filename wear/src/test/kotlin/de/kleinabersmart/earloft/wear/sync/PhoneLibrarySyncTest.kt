package de.kleinabersmart.earloft.wear.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.AbsTokenRenewal
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.sync.CollectionSourceResolver
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.sync.MergeResolver
import de.kleinabersmart.earloft.core.data.sync.PlaylistSyncer
import de.kleinabersmart.earloft.core.data.sync.SweepGuard
import de.kleinabersmart.earloft.core.data.sync.SyncNotices
import de.kleinabersmart.earloft.core.data.sync.SyncQueueStarter
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.datasource.jellyfin.JellyfinDataSourceFactory
import de.kleinabersmart.earloft.core.datasource.local.LocalDataSource
import de.kleinabersmart.earloft.core.datasource.subsonic.SubsonicDataSourceFactory
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinClientInfo
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServerRegistry
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicApi
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServerRegistry
import de.kleinabersmart.earloft.core.wearsync.WearBookDto
import de.kleinabersmart.earloft.core.wearsync.WearBookTrackDto
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeReportDto
import de.kleinabersmart.earloft.core.wearsync.WearManifest
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPodcastFeedDto
import de.kleinabersmart.earloft.core.wearsync.WearPodcastManifest
import de.kleinabersmart.earloft.core.wearsync.WearSelectionDto
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearServerDto
import de.kleinabersmart.earloft.core.wearsync.WearServerSourceDto
import de.kleinabersmart.earloft.core.wearsync.WearServersDto
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto
import de.kleinabersmart.earloft.core.wearsync.WearSongDto
import de.kleinabersmart.earloft.core.wearsync.WearSpokenServerDto
import de.kleinabersmart.earloft.wear.download.WatchState
import de.kleinabersmart.earloft.wear.download.WearDownloadPlanner
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's state applied on the watch: servers, songs and podcasts arrive, an unchanged state is not applied again,
 * something unreadable doesn't take the rest with it, and progress goes out before episodes are replaced. Real database,
 * mirrors and planner; only the DataItems and the link to the phone are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhoneLibrarySyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase
    private lateinit var state: WatchState
    private lateinit var sync: PhoneLibrarySync
    private val phoneData = FakePhoneData()
    private val phone = FakePhoneLink()
    private val scheduler = RecordingScheduler()
    private val rechecks = mutableListOf<Long>()
    private val guard = RemovalGuard(context) { rechecks += it }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val secrets = MemorySecrets()
        val client = OkHttpClient()
        val stamps = CredentialsStamps(secrets)
        val registry = DataSourceRegistry(
            localDataSource = LocalDataSource(context),
            sourceDao = db.sourceDao(),
            secretStore = secrets,
            subsonicFactory = SubsonicDataSourceFactory(context, SubsonicApi(client)),
            serverRegistry = SubsonicServerRegistry(),
            jellyfinFactory = JellyfinDataSourceFactory(context, JellyfinApi(client, JellyfinClientInfo("Test", "1"))),
            jellyfinRegistry = JellyfinServerRegistry(),
            librarySettings = LibrarySettings(context),
        )
        val absApi = AbsApi(client)
        val absRegistry = AbsServerRegistry()
        val authApi = AbsAuthApi(client)
        val audiobookshelf = AudiobookshelfServers(db.bookDao(), secrets, absApi, authApi, AbsTokenRenewal(secrets, absRegistry, authApi), absRegistry, stamps)
        val librarySyncer = LibrarySyncer(
            database = db,
            sourceDao = db.sourceDao(),
            mergeDao = db.mergeDao(),
            libraryDao = db.libraryDao(),
            mergeResolver = MergeResolver(db.mergeDao(), db.syncDao(), CollectionSourceResolver(db.mergeDao(), db.syncDao())),
            changeListeners = emptySet(),
            syncQueue = SyncQueueStarter(db.syncDao(), NoSyncScheduler),
            playlistSyncer = PlaylistSyncer(db, db.playlistDao(), db.syncDao(), SyncNotices()),
            syncDao = db.syncDao(),
            sweepGuard = SweepGuard(context),
        )
        state = WatchState(context)
        val episodeQueue = EpisodeQueue(context, db.bookDao())
        val reported = ReportedEpisodes(context)
        val planner = WearDownloadPlanner(
            database = db,
            wearDao = db.wearDao(),
            playlistDao = db.playlistDao(),
            downloadDao = db.downloadDao(),
            storage = DownloadStorage(context),
            state = state,
            scheduler = scheduler,
            coverFiles = CoverFiles(context),
            podcastDao = db.podcastDao(),
            bookDao = db.bookDao(),
            episodeQueue = episodeQueue,
        )
        sync = PhoneLibrarySync(
            context, phoneData, phone,
            WearServerMirror(db, db.sourceDao(), db.libraryDao(), secrets, registry, stamps, emptySet()),
            audiobookshelf,
            ManifestApplier(db, db.sourceDao(), db.wearDao(), db.playlistDao(), librarySyncer),
            PodcastMirror(db, db.podcastDao(), db.bookDao(), reported, EpisodeStorage(context), BookStorage(context)),
            WatchEpisodeReporter(phoneData, db.bookDao(), db.podcastDao(), db.statsDao(), reported, BookProgressSync(context, db.bookDao(), audiobookshelf, absApi, PlaybackPolicy())),
            episodeQueue,
            state,
            planner,
            guard,
            db.sourceDao(),
            db.wearDao(),
            db.bookDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- The phone's state ---

    private val navidrome = WearServerDto(sourceId = "nd", name = "Navidrome", baseUrl = "https://music.example.org", username = "me")

    private fun song(key: String, remoteId: String) = WearSongDto(
        key = key, title = "Song $remoteId", artistDisplay = "Artist", album = "Album", albumArtist = "Artist", durationMs = 1_000,
        serverSources = listOf(WearServerSourceDto("nd:$remoteId", "nd", remoteId, mimeType = "audio/flac", sizeBytes = 1_000)),
    )

    private fun manifest(vararg remoteIds: String) = WearManifest(
        selections = listOf(WearSelectionDto("album:1", WearSelectionKind.ALBUM, title = "Album", songKeys = remoteIds.map { "nd:$it" })),
        songs = remoteIds.map { song("nd:$it", it) },
    )

    private val feed = WearPodcastFeedDto("f1", "https://example.org/feed", "Podcast")

    private fun podcasts(vararg guids: String) = WearPodcastManifest(
        feeds = listOf(feed),
        episodes = guids.map { WearEpisodeDto("f1", it, "Episode $it", durationMs = 600_000, audioUrl = "https://example.org/$it.mp3") },
    )

    /** What the phone has published. */
    private fun publish(
        servers: WearServersDto? = WearServersDto(servers = listOf(navidrome)),
        manifest: WearManifest? = manifest("s1", "s2"),
        podcasts: WearPodcastManifest? = podcasts("e1"),
    ) {
        phoneData.items.clear()
        servers?.let { phoneData.publish(WearPaths.SERVERS, WearCodec.encode(it)) }
        manifest?.let { phoneData.publish(WearPaths.MANIFEST, WearCodec.encodeManifest(it), asset = true) }
        podcasts?.let { phoneData.publish(WearPaths.PODCASTS, WearCodec.encodeCompressed(it), asset = true) }
    }

    private suspend fun songTitles() = db.libraryDao().getSongRows(db.wearDao().getSelections().flatMap { selection ->
        db.playlistDao().getSongIds(selection.refId!!)
    }).map { it.title }.sorted()

    private suspend fun episodeGuids() = db.bookDao().booksOf("f1").map { it.remoteId }.sorted()

    // --- Tests ---

    @Test
    fun `servers, songs and podcasts from the phone arrive and the planner runs`() = runTest {
        publish()

        sync.sync()

        assertNotNull(db.sourceDao().get("nd"))
        assertEquals(listOf("Song s1", "Song s2"), songTitles())
        assertEquals(listOf("e1"), episodeGuids())
        assertTrue("passwords are asked for the new server", phone.missingRequested.flatten().contains("nd"))
        assertEquals("new downloads replace a waiting job", listOf(REPLACE), scheduler.calls)
        assertNotNull(state.appliedPhoneStamp)
    }

    @Test
    fun `an unchanged state is not applied again but still plans`() = runTest {
        publish()
        sync.sync()
        // Removed on the watch in the meantime; only a new state from the phone brings it back.
        db.wearDao().delete("album:1")

        sync.sync()
        assertTrue(db.wearDao().getSelections().isEmpty())
        assertEquals(listOf(REPLACE, RESUME), scheduler.calls)

        publish(manifest = manifest("s1", "s2", "s3"))
        sync.sync()
        assertEquals(listOf("Song s1", "Song s2", "Song s3"), songTitles())
    }

    @Test
    fun `unreadable servers keep the known ones and their songs`() = runTest {
        publish()
        sync.sync()

        publish()
        phoneData.publish(WearPaths.SERVERS, "{ not json".toByteArray())
        sync.sync()

        assertNotNull(db.sourceDao().get("nd"))
        assertEquals(listOf("Song s1", "Song s2"), songTitles())
    }

    @Test
    fun `an unreadable manifest does not hold up the podcasts`() = runTest {
        publish(manifest = null)
        phoneData.publish(WearPaths.MANIFEST, "not gzip".toByteArray(), asset = true)

        sync.sync()

        assertEquals(listOf("e1"), episodeGuids())
        assertTrue(songTitles().isEmpty())
    }

    @Test
    fun `a manifest whose file has not arrived yet is applied once it is there`() = runTest {
        publish()
        // The DataItem is there, its asset not yet (phone out of range during the transfer).
        phoneData.withholdAssets = true
        // review5 P71: reported as not done, so the job tries again instead of waiting for the next change on the phone.
        assertFalse(sync.sync())
        assertTrue(songTitles().isEmpty())
        assertNull(state.appliedPhoneStamp)

        phoneData.withholdAssets = false
        assertTrue(sync.sync())
        assertEquals(listOf("Song s1", "Song s2"), songTitles())
    }

    @Test
    fun `progress goes to the phone before the episodes are replaced`() = runTest {
        publish(podcasts = podcasts("e1", "e2"))
        sync.sync()
        val dropped = db.bookDao().booksOf("f1").single { it.remoteId == "e2" }.bookId
        db.bookDao().upsertProgress(BookProgressEntity(dropped, positionMs = 120_000, durationMs = 600_000, updatedAt = System.currentTimeMillis()))

        publish(podcasts = podcasts("e1"))
        sync.sync()

        assertEquals(listOf("e1"), episodeGuids())
        val reported = phoneData.reports.flatMap { it.progress }.single()
        assertEquals("e2", reported.guid)
        assertEquals(120_000L, reported.progress.positionMs)
    }

    @Test
    fun `progress goes to the phone before a removed audiobook server takes its books`() = runTest {
        val abs = WearSpokenServerDto("abs1", "Audiobookshelf", "https://books.example.org")
        val audiobook = WearBookDto("abs1", "b1", "Book", durationMs = 3_600_000, tracks = listOf(WearBookTrackDto(0, "f1", durationMs = 3_600_000)))
        publish(
            servers = WearServersDto(servers = listOf(navidrome), spokenServers = listOf(abs)),
            podcasts = podcasts("e1").copy(books = listOf(audiobook)),
        )
        sync.sync()
        val book = db.bookDao().booksOf("abs1").single().bookId
        db.bookDao().upsertProgress(BookProgressEntity(book, positionMs = 900_000, durationMs = 3_600_000, updatedAt = System.currentTimeMillis()))

        // Removed on the phone, along with its books in the episode manifest.
        publish(podcasts = podcasts("e1"))
        sync.sync()

        assertTrue(db.bookDao().booksOf("abs1").isEmpty())
        val reported = phoneData.reports.flatMap { it.progress }.single()
        assertEquals("b1", reported.guid)
        assertEquals(900_000L, reported.progress.positionMs)
    }

    @Test
    fun `a phone that suddenly reports nothing removes nothing until it is confirmed on the watch`() = runTest {
        publish(manifest = manifest("s1", "s2"), podcasts = podcasts("e1", "e2"))
        sync.sync()
        db.wearDao().upsert(db.wearDao().getSelections().single().copy(key = "album:2"))

        // A reinstalled phone app before its restore: no servers, no selections, no episodes.
        publish(servers = WearServersDto(), manifest = WearManifest(), podcasts = WearPodcastManifest())
        sync.sync()

        assertNotNull(db.sourceDao().get("nd"))
        assertEquals(listOf("Song s1", "Song s2"), songTitles())
        assertEquals(listOf("e1", "e2"), episodeGuids())
        assertTrue(guard.held.value)
        assertEquals(listOf(RemovalGuard.HOLD_MS), rechecks)

        guard.confirm()
        sync.sync()

        assertNull(db.sourceDao().get("nd"))
        assertTrue(episodeGuids().isEmpty())
        assertFalse(guard.held.value)
    }

    @Test
    fun `a phone that brings everything back ends the hold`() = runTest {
        publish()
        sync.sync()
        publish(servers = WearServersDto())
        sync.sync()
        assertTrue(guard.held.value)

        publish()
        sync.sync()

        assertFalse(guard.held.value)
        assertNotNull(db.sourceDao().get("nd"))
    }

    @Test
    fun `single removals apply at once, but not from a phone whose database is new`() = runTest {
        publish(servers = WearServersDto(servers = listOf(navidrome), databaseId = "1"), podcasts = podcasts("e1", "e2", "e3"))
        sync.sync()

        publish(servers = WearServersDto(servers = listOf(navidrome), databaseId = "1"), podcasts = podcasts("e1", "e2"))
        sync.sync()
        assertEquals(listOf("e1", "e2"), episodeGuids())

        // Reinstalled: the restore brought the servers back, one episode is missing.
        publish(servers = WearServersDto(servers = listOf(navidrome), databaseId = "2"), podcasts = podcasts("e1"))
        sync.sync()
        assertEquals(listOf("e1", "e2"), episodeGuids())
        assertTrue(guard.held.value)
    }

    @Test
    fun `the hold ends by itself after a day`() {
        val now = 1_000_000L
        assertFalse(guard.allows(drops = true, now = now))
        assertFalse(guard.allows(drops = true, now = now + RemovalGuard.HOLD_MS - 1))
        assertTrue(guard.allows(drops = true, now = now + RemovalGuard.HOLD_MS))
        assertFalse(guard.held.value)
    }

    @Test
    fun `a confirmation counts once, also when the hold ended by itself before`() {
        val now = 1_000_000L
        assertFalse(guard.allows(drops = true, now = now))
        guard.confirm()
        // The phone came back in the meantime.
        assertTrue(guard.allows(drops = false, now = now + 1))

        assertFalse(guard.allows(drops = true, now = now + 2))
    }

    @Test
    fun `confirming without a hold lets no later drop through`() {
        guard.confirm()

        assertFalse(guard.allows(drops = true, now = 1_000_000L))
    }

    @Test
    fun `clearing on the watch itself is applied without asking again`() = runTest {
        publish(manifest = manifest("s1", "s2"), podcasts = podcasts("e1", "e2"))
        sync.sync()
        db.wearDao().upsert(db.wearDao().getSelections().single().copy(key = "album:2"))

        // "Remove all" in the storage screen: the phone answers with an empty selection.
        guard.expectRemoval()
        publish(manifest = WearManifest(), podcasts = WearPodcastManifest())
        sync.sync()

        assertTrue(episodeGuids().isEmpty())
        assertFalse(guard.held.value)
    }

    @Test
    fun `a removal asked for on the watch is expected only for a few minutes`() {
        val now = 1_000_000L
        guard.expectRemoval(now)

        assertTrue(guard.allows(drops = true, now = now + RemovalGuard.EXPECTED_REMOVAL_MS - 1))
        assertFalse(guard.allows(drops = true, now = now + RemovalGuard.EXPECTED_REMOVAL_MS))
    }

    @Test
    fun `what counts as a drop`() {
        assertFalse(RemovalGuard.drops(known = 3, kept = 3, newPhone = true, atLeast = 2))
        assertTrue(RemovalGuard.drops(known = 3, kept = 2, newPhone = true, atLeast = 2))
        assertFalse("one of one selection", RemovalGuard.drops(known = 1, kept = 0, newPhone = false, atLeast = 2))
        assertTrue("the only server", RemovalGuard.drops(known = 1, kept = 0, newPhone = false, atLeast = 1))
        assertTrue(RemovalGuard.drops(known = 2, kept = 0, newPhone = false, atLeast = 2))
        assertFalse(RemovalGuard.drops(known = 3, kept = 1, newPhone = false, atLeast = 2))
        assertTrue(RemovalGuard.drops(known = 4, kept = 1, newPhone = false, atLeast = 2))
        assertFalse(RemovalGuard.drops(known = 4, kept = 2, newPhone = false, atLeast = 2))
    }

    @Test
    fun `a restart the last run could not plan anymore is done by the next one`() = runTest {
        publish()
        sync.sync()
        // Quality changed on the phone: the settings were saved, then the run ended before planning (review5 P66).
        state.downloadRestartPending = true

        sync.sync()

        assertEquals(listOf(REPLACE, RESTART), scheduler.calls)
        assertFalse(state.downloadRestartPending)
    }

    @Test
    fun `changed ReplayGain or areas only plan and apply nothing again`() = runTest {
        publish()
        sync.sync()
        // Removed on the watch meanwhile: applying the manifest again would bring it back.
        db.wearDao().delete("album:1")

        // review5 P67: every change of ReplayGain, pre-amp or areas decoded and applied everything again.
        phoneData.publish(WearPaths.SETTINGS, WearCodec.encode(WearSettingsDto(preAmpDb = 3f, areas = listOf(SpokenMode.MUSIC))))
        sync.sync()

        assertTrue(db.wearDao().getSelections().isEmpty())
        assertEquals(listOf(REPLACE, RESUME), scheduler.calls)
        assertEquals(3f, state.settings.value.preAmpDb)
    }

    @Test
    fun `changed settings restart and a returning phone replaces the download schedule`() = runTest {
        publish()
        sync.sync()
        sync.sync()
        assertEquals("nothing new: the waiting job stays", listOf(REPLACE, RESUME), scheduler.calls)

        // Changed settings also restart a running job, which would load the rest under the old ones (review4 8.6 Y3).
        phoneData.publish(WearPaths.SETTINGS, WearCodec.encode(WearSettingsDto(requireCharging = false)))
        sync.sync()
        assertEquals(listOf(REPLACE, RESUME, RESTART), scheduler.calls)
        assertFalse(state.settings.value.requireCharging)

        // The job waited for the charger because the phone was missing; now it is back.
        state.waitingForPhone = true
        sync.sync()
        assertEquals(listOf(REPLACE, RESUME, RESTART, REPLACE), scheduler.calls)
        assertFalse(state.waitingForPhone)
        assertEquals(1, phone.returnWithdrawn)

        // Still missing: it keeps waiting.
        state.waitingForPhone = true
        phone.nodeId = null
        sync.sync()
        assertEquals(RESUME, scheduler.calls.last())
        assertTrue(state.waitingForPhone)
        assertEquals(1, phone.returnWithdrawn)
    }

    // --- Fakes ---

    private class FakePhoneData : PhoneData {
        /** Path → (bytes, published as an asset). */
        val items = mutableMapOf<String, Pair<ByteArray, Boolean>>()
        var withholdAssets = false
        val reports = mutableListOf<WearEpisodeReportDto>()

        fun publish(path: String, bytes: ByteArray, asset: Boolean = false) {
            items[path] = bytes to asset
        }

        override suspend fun payloads(path: String): Map<String, ByteArray> =
            items[path]?.takeIf { !it.second }?.let { mapOf(NODE to it.first) }.orEmpty()

        override suspend fun hashes(path: String): Map<String, String> =
            items[path]?.let { mapOf(NODE to WearCodec.hash(it.first)) }.orEmpty()

        override suspend fun assetPayloads(path: String): Map<String, ByteArray> =
            items[path]?.takeIf { it.second && !withholdAssets }?.let { mapOf(NODE to it.first) }.orEmpty()

        override suspend fun put(path: String, payload: ByteArray): Boolean {
            reports += WearCodec.decode<WearEpisodeReportDto>(payload)
            return true
        }
    }

    private class FakePhoneLink : PhoneLink {
        var nodeId: String? = NODE
        val requested = mutableListOf<Collection<String>>()
        val missingRequested = mutableListOf<Collection<String>>()
        var returnWithdrawn = 0

        override suspend fun connectedPhoneNodeId(): String? = nodeId

        override suspend fun requestCredentials(sourceIds: Collection<String>, phoneNodeId: String) {
            requested += sourceIds
        }

        override suspend fun requestMissingCredentials(sourceIds: Collection<String>, phoneNodeId: String) {
            missingRequested += sourceIds
        }

        override suspend fun withdrawReturnRequest() {
            returnWithdrawn++
        }
    }

    private class RecordingScheduler : DownloadScheduler {
        val calls = mutableListOf<String>()

        override suspend fun enqueue(replace: Boolean) {
            calls += if (replace) REPLACE else "enqueue"
        }

        override suspend fun resume() {
            calls += RESUME
        }

        override suspend fun restart() {
            calls += RESTART
        }
    }

    private object NoSyncScheduler : SyncScheduler {
        override suspend fun enqueue() = Unit
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private companion object {
        const val NODE = "phone-1"
        const val REPLACE = "replace"
        const val RESTART = "restart"
        const val RESUME = "resume"
    }
}
