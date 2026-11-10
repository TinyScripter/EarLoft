package de.kleinabersmart.earloft

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
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.cast.CastVolumeIndicator
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.share.IncomingFeed
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.sync.LibraryRefresher
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.playback.EXTRA_OPEN_NOW_PLAYING
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.notification.NewContentTarget
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

    @Inject lateinit var playbackConnection: PlaybackConnection

    /** Der Tipp auf die Benachrichtigung der laufenden Musik soll immer die Wiedergabe-Ansicht zeigen. */
    private var openNowPlaying by mutableStateOf(false)

    /** Von einer anderen App geteilte Podcast-Adresse (Konzept 13); die Oberfläche öffnet sie als Vorschau. */
    private var sharedFeedUrl by mutableStateOf<String?>(null)

    /** Tipp auf eine Benachrichtigung über Neues; gesetzt erst, wenn der Bereich gespeichert ist (siehe [openNewContent]). */
    private var newContentTarget by mutableStateOf<NewContentTarget?>(null)

    /** Zählt die abgefangenen Lautstärketasten beim Casten; jede Änderung blendet [CastVolumeIndicator] ein. */
    private var castVolumePresses by mutableIntStateOf(0)

    /**
     * Beim Casten ändern die Lautstärketasten die Lautstärke des Cast-Geräts und die App zeigt sie selbst an: Die
     * System-Oberfläche des Pixels zeigt ihren Dialog für Cast-Sitzungen nur sporadisch. Im Hintergrund bleibt es beim
     * System. Den aktuellen [PlaybackConnection.state] hält die App-Oberfläche verbunden, solange sie sichtbar ist.
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
        openSharedFeed(intent)
        openNewContent(NewContentTarget.from(intent))
    }

    /**
     * Neue Musik, Folgen oder Bücher: Erst wird der Bereich gewählt (Start und Bibliothek fahren dorthin), dann öffnet
     * die Oberfläche Start bzw. die Seite des einzelnen Albums, Podcasts oder Buchs. Andersherum schlösse der Wechsel des
     * Bereichs die eben geöffnete Seite wieder.
     */
    private fun openNewContent(target: NewContentTarget?) {
        if (target == null) return
        lifecycleScope.launch {
            spokenSettings.setMode(target.mode)
            newContentTarget = target
        }
    }

    /** Geteilter Text oder ein Link wie `feed://…`: Die Adresse öffnet die Vorschau als Probeabo (Konzept 12.10, 13). */
    private fun openSharedFeed(intent: Intent) {
        val address = when (intent.action) {
            // EXTRA_TEXT ist eine CharSequence; formatierter Text (Spanned) käme über getStringExtra als null an.
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(IncomingFeed::fromText)
            Intent.ACTION_VIEW -> intent.dataString?.let(IncomingFeed::fromLink)
            else -> null
        }
        if (address != null) sharedFeedUrl = address
    }

    /** Sprachsuche des Systems („Spiele … mit EarLoft“): abspielen und die Wiedergabe-Ansicht zeigen. */
    private fun playFromSearch(intent: Intent) {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        playbackConnection.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        openNowPlaying = true
    }

    /**
     * Abgleiche beim Öffnen der App, nur für die eingeschalteten Bereiche; der bloße Start des Prozesses (Hintergrund-Jobs,
     * Uhr) gleicht nichts ab.
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
        // Nur beim frischen Start: Nach einer Neuerstellung (Drehung, Prozess) steht der Intent noch an der Activity. Was die
        // Oberfläche bis dahin nicht abgeholt hat (etwa während der Frage nach der Berechtigung), kommt aus dem Zustand.
        if (savedInstanceState == null) {
            openNowPlaying = intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)
            playFromSearch(intent)
            openSharedFeed(intent)
            openNewContent(NewContentTarget.from(intent))
        } else {
            openNowPlaying = savedInstanceState.getBoolean(STATE_OPEN_NOW_PLAYING)
            sharedFeedUrl = savedInstanceState.getString(STATE_SHARED_FEED)
            newContentTarget = NewContentTarget.from(savedInstanceState.getBundle(STATE_NEW_CONTENT))
        }
        setContent {
            val themeMode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = null)
            val systemColorsSetting by themeSettings.systemColors.collectAsStateWithLifecycle(initialValue = null)
            val useLocalMedia by librarySettings.useLocalMedia.collectAsStateWithLifecycle(initialValue = null)
            // Bis die Einstellungen gelesen sind (wenige Millisekunden): nichts zeichnen statt kurz falsch.
            val mode = themeMode ?: return@setContent
            val systemColors = systemColorsSetting ?: return@setContent
            val localMedia = useLocalMedia ?: return@setContent
            val alwaysNavigationBar by themeSettings.alwaysShowNavigationBar.collectAsStateWithLifecycle(initialValue = true)
            val playerCoverColors by themeSettings.playerCoverColors.collectAsStateWithLifecycle(initialValue = true)

            val darkTheme = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Symbole der Systemleisten passend zur App statt zum System.
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

        // Wie die Standardwerte von enableEdgeToEdge (dort nicht öffentlich).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
