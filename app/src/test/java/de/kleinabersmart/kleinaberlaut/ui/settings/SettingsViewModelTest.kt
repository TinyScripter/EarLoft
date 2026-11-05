package de.kleinabersmart.kleinaberlaut.ui.settings

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import de.kleinabersmart.kleinaberlaut.core.data.podcast.DatabaseExporter
import de.kleinabersmart.kleinaberlaut.core.data.podcast.DatabaseImporter
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastDirectorySearch
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastSettings
import de.kleinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import de.kleinabersmart.kleinaberlaut.core.data.repository.ArtworkSettings
import de.kleinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import de.kleinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import de.kleinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.kleinaberlaut.core.data.spoken.AuthorImages
import de.kleinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import de.kleinabersmart.kleinaberlaut.core.data.spoken.SpokenSettings
import de.kleinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import de.kleinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import de.kleinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import de.kleinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import de.kleinabersmart.kleinaberlaut.core.data.sync.SyncFailure
import de.kleinabersmart.kleinaberlaut.core.data.sync.SyncSettings
import de.kleinabersmart.kleinaberlaut.core.database.dao.SourceDao
import de.kleinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import de.kleinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import de.kleinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import de.kleinabersmart.kleinaberlaut.core.datasource.SourceBatch
import de.kleinabersmart.kleinaberlaut.core.download.DownloadRepository
import de.kleinabersmart.kleinaberlaut.core.download.DownloadSettings
import de.kleinabersmart.kleinaberlaut.core.model.SourceType
import de.kleinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import de.kleinabersmart.kleinaberlaut.testing.MainDispatcherRule
import de.kleinabersmart.kleinaberlaut.testing.awaitReal
import de.kleinabersmart.kleinaberlaut.ui.spoken.OpmlImporter
import de.kleinabersmart.kleinaberlaut.ui.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.inject.Inject

/** Die Serverliste der Einstellungen mit dem Grund des letzten fehlgeschlagenen Abgleichs (Review A3, S2). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var sourceDao: SourceDao
    @Inject lateinit var syncer: LibrarySyncer

    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var refresher: LibraryRefresher
    @Inject lateinit var downloadSettings: DownloadSettings
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var syncSettings: SyncSettings
    @Inject lateinit var replayGainSettings: ReplayGainSettings
    @Inject lateinit var themeSettings: ThemeSettings
    @Inject lateinit var librarySettings: LibrarySettings
    @Inject lateinit var spokenSettings: SpokenSettings
    @Inject lateinit var podcastSettings: PodcastSettings
    @Inject lateinit var artworkSettings: ArtworkSettings
    @Inject lateinit var artworkRepository: ArtworkRepository
    @Inject lateinit var authorImages: AuthorImages
    @Inject lateinit var registry: DataSourceRegistry
    @Inject lateinit var spokenRefresher: SpokenRefresher
    @Inject lateinit var podcastRefresher: PodcastRefresher
    @Inject lateinit var podcastRepository: PodcastRepository
    @Inject lateinit var opmlImporter: OpmlImporter
    @Inject lateinit var directorySearch: PodcastDirectorySearch
    @Inject lateinit var databaseImporter: DatabaseImporter
    @Inject lateinit var databaseExporter: DatabaseExporter
    @Inject lateinit var statsRepository: StatsRepository
    @Inject @ApplicationContext lateinit var context: Context
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
    @Inject lateinit var audiobookServers: AudiobookshelfServers

    private val viewModel by lazy {
        SettingsViewModel(
            serverRepository, refresher, downloadSettings, downloadRepository, syncSettings, replayGainSettings,
            themeSettings, librarySettings, spokenSettings, podcastSettings, artworkSettings, artworkRepository,
            authorImages, registry, spokenRefresher, podcastRefresher, podcastRepository, opmlImporter, directorySearch,
            databaseImporter, databaseExporter, statsRepository, context, applicationScope, audiobookServers, syncer,
        )
    }

    @Before
    fun setUp() = runTest {
        hilt.inject()
        sourceDao.upsert(SourceEntity(SERVER_ID, SourceType.SUBSONIC, "Navidrome", baseUrl = "https://music.example/", username = "kal"))
    }

    @Test
    fun `a failed sync shows its reason until the next sync succeeds`() = runTest {
        backgroundScope.launch { viewModel.state.collect {} }

        assertFalse(syncer.syncSafely(FakeSource { throw IOException("timeout") }))
        val failed = awaitReal { viewModel.state.first { it.servers.singleOrNull()?.error != null } }
        assertEquals(SyncFailure.Unreachable("timeout"), failed.servers.single().error)
        assertEquals("Navidrome", failed.servers.single().server.name)

        assertTrue(syncer.syncSafely(FakeSource { emit(SourceBatch(emptyList())) }))
        val synced = awaitReal { viewModel.state.first { it.servers.singleOrNull()?.error == null && !it.isLoading } }
        assertNull(synced.servers.single().error)
    }

    /** Ein Server, dessen Vollabgleich [scan] liefert. */
    private class FakeSource(private val scan: suspend FlowCollector<SourceBatch>.() -> Unit) : MusicDataSource {
        override val sourceId = SERVER_ID
        override val type = SourceType.SUBSONIC
        override val displayName = "Navidrome"
        override fun fullScan(): Flow<SourceBatch> = flow(scan)
        override suspend fun resolvePlayback(remoteId: String): PlaybackLocation = error("nicht im Test")
        override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
    }

    private companion object {
        const val SERVER_ID = "server-1"
    }
}
