package de.kleinabersmart.earloft

import android.widget.Toast
import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.cast.CastVolumeIndicator
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.setup.SetupState
import de.kleinabersmart.earloft.core.data.share.IncomingFeed
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.sync.LibraryRefresher
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.playback.EXTRA_OPEN_NOW_PLAYING
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.notification.AppNewContentNotifier
import de.kleinabersmart.earloft.notification.NewContentTarget
import de.kleinabersmart.earloft.notification.NewEpisodeActionReceiver
import de.kleinabersmart.earloft.ui.spoken.BookLauncher
import de.kleinabersmart.earloft.ui.EarLoftApp
import de.kleinabersmart.earloft.ui.permission.AudioPermissionGate
import de.kleinabersmart.earloft.ui.theme.LocalPlayerCoverColors
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import de.kleinabersmart.earloft.ui.theme.ThemeSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.SpokenMode
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var libraryRefresher: LibraryRefresher
    @Inject lateinit var spokenRefresher: SpokenRefresher
    @Inject lateinit var podcastRefresher: PodcastRefresher
    @Inject lateinit var spokenSettings: SpokenSettings

    @Inject lateinit var themeSettings: ThemeSettings

    @Inject lateinit var librarySettings: LibrarySettings

    @Inject lateinit var setupState: SetupState

    @Inject lateinit var playbackConnection: PlaybackConnection

    @Inject lateinit var bookLauncher: BookLauncher

    /** Tapping the notification of the playing music should always show the playback screen. */
    private var openNowPlaying by mutableStateOf(false)

    /** Podcast address shared by another app (concept 13); the UI opens it as a preview. */
    private var sharedFeedUrl by mutableStateOf<String?>(null)

    /** Tap on a new-content notification; only set once the area is stored (see [openNewContent]). */
    private var newContentTarget by mutableStateOf<NewContentTarget?>(null)

    /** Counts the intercepted volume keys while casting; every change shows [CastVolumeIndicator]. */
    private var castVolumePresses by mutableIntStateOf(0)

    /**
     * While casting, the volume keys change the cast device's volume and the app shows it itself: the
     * Pixel's system UI only sporadically shows its dialog for cast sessions. In the background the
     * system handles it. The app UI keeps the current [PlaybackConnection.state] connected while it is visible.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isCastVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        playbackConnection.changeRemoteVolume(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        castVolumePresses++
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isCastVolumeKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isCastVolumeKey(keyCode: Int): Boolean =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && playbackConnection.state.value.isRemote

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) openNowPlaying = true
        playFromSearch(intent)
        playNewEpisode(intent)
        openSharedFeed(intent)
        openNewContent(NewContentTarget.from(intent))
    }

    /**
     * "Play" in the new-episode notification: play and show the playback screen. Goes through the app
     * so the mobile data prompt can appear; afterwards the notification disappears.
     */
    private fun playNewEpisode(intent: Intent) {
        if (intent.action != AppNewContentNotifier.ACTION_PLAY_EPISODE) return
        // Only from our own notification: MainActivity is exported, the alias isn't (review D30).
        if (intent.component?.className != AppNewContentNotifier.PLAY_ALIAS) return
        val bookId = intent.getLongExtra(NewEpisodeActionReceiver.EXTRA_BOOK_ID, -1).takeIf { it >= 0 } ?: return
        NotificationManagerCompat.from(this).cancel(AppNewContentNotifier.notificationId(SpokenMode.PODCASTS))
        lifecycleScope.launch {
            spokenSettings.setMode(SpokenMode.PODCASTS)
            if (bookLauncher.playAndAwait(bookId)) openNowPlaying = true
        }
    }

    /**
     * New music, episodes or books: first the area is selected (Home and Library switch to it), then
     * the UI opens Home or the page of the single album, podcast or book. The other way round, switching the
     * area would close the page that was just opened.
     */
    private fun openNewContent(target: NewContentTarget?) {
        if (target == null) return
        lifecycleScope.launch {
            spokenSettings.setMode(target.mode)
            newContentTarget = target
        }
    }

    /** Shared text or a link like `feed://…`: the address opens the preview as a trial subscription (concept 12.10, 13). */
    private fun openSharedFeed(intent: Intent) {
        val address = when (intent.action) {
            // EXTRA_TEXT is a CharSequence; formatted text (Spanned) would arrive as null via getStringExtra.
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(IncomingFeed::fromText)
            Intent.ACTION_VIEW -> intent.dataString?.let(IncomingFeed::fromLink)
            else -> null
        }
        if (address != null) sharedFeedUrl = address
    }

    /**
     * System voice search ("Play … with EarLoft"): play and show the playback screen. Without results nothing plays (the queue
     * stays as it is) and a hint says so.
     */
    private fun playFromSearch(intent: Intent) {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        lifecycleScope.launch {
            if (playbackConnection.playFromSearch(query)) {
                openNowPlaying = true
            } else {
                val text = if (query.isBlank()) getString(R.string.voice_nothing_to_play) else getString(R.string.voice_nothing_found, query)
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Syncs when the app is opened, only for the enabled areas; merely starting the process (background jobs,
     * watch) syncs nothing.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val areas = spokenSettings.areas.first()
            if (SpokenMode.MUSIC in areas) libraryRefresher.refreshQuick()
            spokenRefresher.refreshQuick(books = SpokenMode.BOOKS in areas, podcasts = SpokenMode.PODCASTS in areas)
            if (SpokenMode.PODCASTS in areas) podcastRefresher.refreshStale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: after recreation (rotation, process death) the intent is still on the activity. Whatever the
        // UI hasn't picked up by then (e.g. while asking for the permission) comes from the saved state.
        // From "Recents" the system redelivers the intent of the first start (play, voice search, shared
        // link); but that job was already done and must not run again.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            openNowPlaying = intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)
            playFromSearch(intent)
            playNewEpisode(intent)
            openSharedFeed(intent)
            openNewContent(NewContentTarget.from(intent))
        } else if (savedInstanceState != null) {
            openNowPlaying = savedInstanceState.getBoolean(STATE_OPEN_NOW_PLAYING)
            sharedFeedUrl = savedInstanceState.getString(STATE_SHARED_FEED)
            newContentTarget = NewContentTarget.from(savedInstanceState.getBundle(STATE_NEW_CONTENT))
        }
        setContent {
            val themeMode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = null)
            val systemColorsSetting by themeSettings.systemColors.collectAsStateWithLifecycle(initialValue = null)
            val useLocalMedia by librarySettings.useLocalMedia.collectAsStateWithLifecycle(initialValue = null)
            val setupPending by setupState.pending.collectAsStateWithLifecycle(initialValue = null)
            // Until the settings are read (a few milliseconds; on first start until the backup has been
            // restored): draw nothing rather than briefly something wrong.
            val mode = themeMode ?: return@setContent
            val systemColors = systemColorsSetting ?: return@setContent
            val localMedia = useLocalMedia ?: return@setContent
            val showSetup = setupPending ?: return@setContent
            val alwaysNavigationBar by themeSettings.alwaysShowNavigationBar.collectAsStateWithLifecycle(initialValue = true)
            val hideMiniPlayerOnScroll by themeSettings.hideMiniPlayerOnScroll.collectAsStateWithLifecycle(initialValue = true)
            val playerCoverColors by themeSettings.playerCoverColors.collectAsStateWithLifecycle(initialValue = true)

            val darkTheme = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // System bar icons matching the app instead of the system.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose { }
            }

            EarLoftTheme(darkTheme = darkTheme, systemColors = systemColors) {
                Box(Modifier.fillMaxSize()) {
                    AudioPermissionGate(
                        enabled = localMedia,
                        onGranted = libraryRefresher::refreshNow,
                        onSkip = { lifecycleScope.launch { librarySettings.setUseLocalMedia(false) } },
                    ) {
                        CompositionLocalProvider(LocalPlayerCoverColors provides playerCoverColors) {
                            EarLoftApp(
                                openNowPlaying = openNowPlaying,
                                onNowPlayingOpened = { openNowPlaying = false },
                                sharedFeedUrl = sharedFeedUrl,
                                onSharedFeedOpened = { sharedFeedUrl = null },
                                newContentTarget = newContentTarget,
                                onNewContentOpened = { newContentTarget = null },
                                alwaysShowNavigationBar = alwaysNavigationBar,
                                hideMiniPlayerOnScroll = hideMiniPlayerOnScroll,
                                showSetup = showSetup,
                                onSetupFinished = { lifecycleScope.launch { setupState.finish() } },
                            )
                        }
                    }
                    CastVolumeIndicator(
                        presses = castVolumePresses,
                        state = playbackConnection.state,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPEN_NOW_PLAYING, openNowPlaying)
        outState.putString(STATE_SHARED_FEED, sharedFeedUrl)
        outState.putBundle(STATE_NEW_CONTENT, newContentTarget?.toBundle())
    }

    private companion object {
        const val STATE_OPEN_NOW_PLAYING = "open_now_playing"
        const val STATE_SHARED_FEED = "shared_feed"
        const val STATE_NEW_CONTENT = "new_content"

        // Like the default values of enableEdgeToEdge (not public there).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
