package de.kleinabersmart.earloft.core.playback

import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver
import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookPlaybackSource
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.di.LoggingExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Optional
import javax.inject.Inject

/**
 * Extra am Start-Intent der Sitzungs-Aktivität (Tipp auf Benachrichtigung bzw. auf den Chip der laufenden Musik auf
 * dem Zifferblatt): Die App soll direkt die Wiedergabe-Ansicht öffnen. Wer sie nicht kennt, startet normal.
 */
const val EXTRA_OPEN_NOW_PLAYING = "de.kleinabersmart.earloft.OPEN_NOW_PLAYING"

/** Wiedergabe-Service mit Media3-Session (Konzept 7.1). */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var sourceResolver: PlaybackSourceResolver
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var favoritesRepository: FavoritesRepository
    @Inject lateinit var artworkRepository: ArtworkRepository
    @Inject lateinit var playbackStateRepository: PlaybackStateRepository
    @Inject lateinit var queuePersistence: QueuePersistence
    @Inject lateinit var nowPlayingObservers: Set<@JvmSuppressWildcards NowPlayingObserver>
    @Inject lateinit var playbackPolicy: PlaybackPolicy
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var streamCache: SimpleCache
    @Inject lateinit var replayGainSettings: ReplayGainSettings
    @Inject internal lateinit var libraryRemovals: LibraryRemovals
    @Inject lateinit var sessionPlayerFactory: Optional<SessionPlayerFactory>
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var bookRepository: BookRepository
    @Inject lateinit var bookProgressSync: BookProgressSync
    @Inject lateinit var spokenSettings: SpokenSettings
    @Inject lateinit var statsRepository: StatsRepository
    @Inject lateinit var absSessionReporter: AbsSessionReporter
    @Inject lateinit var bookPlaybackSource: BookPlaybackSource
    @Inject lateinit var continueListening: ContinueListening
    @Inject lateinit var bookCovers: BookCovers
    @Inject lateinit var servicePlayer: ServicePlayer
    @Inject lateinit var playingSources: PlayingSources
    @Inject lateinit var audioEffectSession: AudioEffectSession
    @Inject internal lateinit var browseTree: BrowseTree

    /** Für Datenbankarbeit, die das Beenden des Service überleben darf (nur suspendierende DataStore-/Room-Aufrufe). */
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    /** Für Player-Zugriffe (Main-Thread). */
    private val mainScope = MainScope() + LoggingExceptionHandler
    private var restoreJob: Job? = null

    private var session: MediaLibrarySession? = null
    private lateinit var playTracker: PlayTracker
    private lateinit var bookListener: BookPlaybackListener
    private var streamPreloader: StreamPreloader? = null
    private val replayGain = ReplayGainProcessor()

    override fun onCreate() {
        super.onCreate()
        // earloft://song/<id> → Datei, content:// oder Stream (Konzept 7.2). Streams laufen über den
        // gemeinsamen OkHttpClient (Auth) und einen LRU-Cache, dessen Schlüssel die sourceUid ist.
        val streamFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = SongDataSource.Factory(
            DefaultDataSource.Factory(this, streamFactory),
            sourceResolver,
            SourceHealth(),
            getString(R.string.song_unavailable),
            replayGain::onSourceResolved,
            playingSources::record,
        )
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = ReplayGainAudioSink(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(replayGain))
                    .build(),
                replayGain,
            )
        }
        val localPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes(C.AUDIO_CONTENT_TYPE_MUSIC), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Uhr: nicht über den Lautsprecher; Wear OS bietet stattdessen die Bluetooth-Ausgabe an (Wear-Konzept 9).
            .setSuppressPlaybackOnUnsuitableOutput(playbackPolicy.requireSuitableOutput)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        localPlayer.addListener(SourceErrorRecovery(localPlayer))
        audioEffectSession.attach(this, localPlayer)
        // Uhr: keine Streams, also nichts vorzuladen.
        if (playbackPolicy.allowStreaming) {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            streamPreloader = StreamPreloader(
                localPlayer,
                streamFactory::createDataSourceForDownloading,
                sourceResolver::candidates,
                isMetered = { connectivity.isActiveNetworkMetered },
                scope = mainScope,
            ).also(localPlayer::addListener)
        }
        localPlayer.addListener(WakeModeListener(localPlayer, playbackPolicy.allowStreaming))
        localPlayer.addListener(ContentTypeListener(localPlayer))
        localPlayer.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                replayGain.shuffleEnabled = shuffleModeEnabled
            }
        })

        // Handy: Cast-fähiger Player um den ExoPlayer; Hörverlauf und Warteschlange folgen dem jeweils aktiven Player.
        val factory = sessionPlayerFactory.orElse(null)
        val player = factory?.create(this, localPlayer) ?: localPlayer
        servicePlayer.attach(player)
        sleepTimer.attach(player.asSleepTimerTarget())
        playTracker = PlayTracker(player) { songId, playedAt, listenedMs ->
            applicationScope.launch { playbackStateRepository.recordPlay(songId, playedAt, listenedMs) }
        }
        player.addListener(playTracker)
        player.addListener(PersistenceListener(player))
        bookListener = BookPlaybackListener(player, bookRepository, bookProgressSync, spokenSettings, statsRepository, absSessionReporter, mainScope, applicationScope) { endedId ->
            continueWithNextEpisode(player, endedId)
        }
        player.addListener(bookListener)
        mainScope.launch {
            libraryRemovals.removals.collect { removeMissingSongs(player) }
        }
        mainScope.launch {
            combine(replayGainSettings.mode, replayGainSettings.preAmpDb, ::Pair)
                .collect { (mode, preAmpDb) -> replayGain.setSettings(mode, preAmpDb) }
        }

        // Bei Büchern und Folgen gelten eigene, einstellbare Sprungweiten, siehe SpokenAwarePlayer.
        val sessionPlayer = SpokenAwarePlayer(player)
        session = MediaLibrarySession.Builder(
            this,
            sessionPlayer,
            LibrarySessionCallback(
                this, libraryRepository, favoritesRepository, queuePersistence, bookPlaybackSource, browseTree, continueListening, factory, mainScope,
            ) {
                restoreJob?.join()
            },
        )
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this, artworkRepository, bookCovers, mainScope)))
            .apply { sessionActivity()?.let { setSessionActivity(it) } }
            .build()

        session?.let { bindFavoriteButton(sessionPlayer, it) }
        session?.let { notifyAreaChanges(it) }
        restoreQueue(player)
        session?.let { notifyContentChanges(it, player) }
    }

    /**
     * Bereiche ein- oder ausgeschaltet: Die Tabs der Wurzel und „Zuletzt“ ändern sich. Android Auto lädt sie daraufhin neu
     * (ob sofort oder erst beim nächsten Verbinden, entscheidet Auto).
     */
    /**
     * „Zuletzt“ und die Weiterhören-Ordner ändern sich mit dem, was läuft. Android Auto merkt sich jede Antwort bis zur nächsten
     * Meldung: Eine Abfrage direkt nach dem Start des Dienstes (vor der wiederhergestellten Warteschlange) blieb sonst leer.
     */
    private fun notifyContentChanges(session: MediaLibrarySession, player: Player) {
        fun notifyAll() {
            for (id in CONTENT_FOLDERS) session.notifyChildrenChanged(id, Int.MAX_VALUE, null)
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = notifyAll()
        })
        restoreJob?.invokeOnCompletion { mainScope.launch { notifyAll() } }
    }

    private fun notifyAreaChanges(session: MediaLibrarySession) {
        mainScope.launch {
            spokenSettings.areas.distinctUntilChanged().drop(1).collect { areas ->
                session.notifyChildrenChanged(MediaIds.ROOT, rootFolders(areas).size, null)
                session.notifyChildrenChanged(MediaIds.HOME, browseTree.children(MediaIds.HOME)?.size ?: 0, null)
            }
        }
    }

    /**
     * Folge-für-Folge (Konzept 12.4): Ist eine Podcast-Folge zu Ende, wird die nächste erst jetzt geladen. Wurde in der
     * Zwischenzeit etwas anderes gestartet, bleibt es dabei.
     */
    private fun continueWithNextEpisode(player: Player, endedId: Long) {
        mainScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                continueListening.nextAfter(endedId)?.let { next ->
                    continueListening.started(next)
                    bookPlaybackSource.prepare(next)
                }
            } ?: return@launch
            val stillWaiting = when (player.playbackState) {
                Player.STATE_ENDED -> player.currentMediaItem?.bookId == endedId
                // Cast: Der Empfänger steht nach dem Ende still und hat die Folge meist schon entladen.
                Player.STATE_IDLE -> player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE &&
                    player.currentMediaItem?.bookId.let { it == null || it == endedId }
                else -> false
            }
            if (stillWaiting) player.playBook(prepared)
        }
    }

    /**
     * Herz neben den Wiedergabetasten (Android Auto, Benachrichtigung); zeigt den Favoriten-Zustand des aktuellen Songs.
     * Bei Büchern und Folgen stattdessen die Spulen-Knöpfe an Stelle von ⏮/⏭; der Pfeil ohne Zahl, weil die
     * Sprungweite einstellbar ist und Media3 nicht für jede ein Symbol hat.
     *
     * Über `setMediaButtonPreferences`, nicht `setCustomLayout`: Nur dann macht Media3 aus Knöpfen mit Player-Befehl
     * (Spulen) Custom Actions der Plattform-Session. Sonst fehlen sie in der Mediensteuerung des Systems und in Android
     * Auto, die nur diese Session sehen.
     */
    private fun bindFavoriteButton(player: SpokenAwarePlayer, session: MediaLibrarySession) {
        var favoriteJob: Job? = null
        fun update() {
            favoriteJob?.cancel()
            val songId = player.currentMediaItem?.songId
            val steps = player.currentSteps
            if (steps != null) {
                session.setMediaButtonPreferences(
                    listOf(
                        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                            .setDisplayName(resources.getQuantityString(R.plurals.spoken_seek_back, steps.backSeconds, steps.backSeconds))
                            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                            .setDisplayName(resources.getQuantityString(R.plurals.spoken_seek_forward, steps.forwardSeconds, steps.forwardSeconds))
                            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                            .build(),
                    ),
                )
                return
            }
            if (songId == null) {
                session.setMediaButtonPreferences(emptyList())
                return
            }
            favoriteJob = mainScope.launch {
                favoritesRepository.isFavorite(songId).collect { favorite ->
                    val button = CommandButton.Builder(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                        .setDisplayName(getString(if (favorite) R.string.favorite_remove else R.string.favorite_add))
                        .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                        .build()
                    session.setMediaButtonPreferences(listOf(button))
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = update()
        })
        mainScope.launch {
            combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, ::Pair).collect { (book, podcast) ->
                player.bookSteps = book
                player.podcastSteps = podcast
                update()
            }
        }
        update()
    }

    /**
     * Lädt die letzte Warteschlange pausiert, damit die App nach einem Neustart dort weitermacht.
     * Hat inzwischen jemand anderes etwas geladen (z. B. per [LibrarySessionCallback.onPlaybackResumption]),
     * bleibt es dabei.
     */
    private fun restoreQueue(player: Player) {
        restoreJob = mainScope.launch {
            // Lief zuletzt ein Buch oder eine Folge, wird diese (pausiert) geladen statt der Musik-Warteschlange.
            val lastBookId = queuePersistence.lastBookId()
            if (lastBookId != null) {
                // Ohne Zurückspulen und ohne Schreiben: Auf diesen Start folgt nicht unbedingt ein Abspielen.
                val prepared = withContext(Dispatchers.IO) { bookPlaybackSource.restore(lastBookId) }
                if (player.mediaItemCount > 0) return@launch
                if (prepared != null) {
                    player.playBook(prepared, autoPlay = false)
                    prepared.pausedSinceMs?.let { bookListener.restoredPaused(lastBookId, it) }
                    return@launch
                }
            }
            val restored = queuePersistence.restore() ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            player.shuffleModeEnabled = restored.shuffle
            player.repeatMode = restored.repeatMode
            player.setMediaItems(restored.items, restored.startIndex, restored.startPositionMs)
        }
    }

    /** Nimmt Titel aus der Warteschlange, deren Song es nicht mehr gibt (z. B. Server entfernt). */
    private suspend fun removeMissingSongs(player: Player) {
        val songIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).songId }.distinct()
        if (songIds.isEmpty()) return
        val existing = libraryRepository.songs(songIds).mapTo(HashSet()) { it.id }
        for (index in player.mediaItemCount - 1 downTo 0) {
            val songId = player.getMediaItemAt(index).songId ?: continue
            if (songId !in existing) player.removeMediaItem(index)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        audioEffectSession.close(this)
        streamPreloader?.release()
        sleepTimer.detach()
        servicePlayer.detach()
        session?.let { session ->
            playTracker.finishCurrent()
            bookListener.finish()
            // Mit einem Buch im Player gehörten dessen Datei und Position sonst in den Stand der Musik-Warteschlange.
            if (session.player.currentMediaItem?.isSpoken != true) queuePersistence.savePosition(session.player, applicationScope)
            session.player.release()
            session.release()
        }
        session = null
        notifyObservers(null)
        mainScope.cancel()
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        // SINGLE_TOP: Ist die App schon offen, bekommt die Activity den Intent per onNewIntent, statt dass er verloren geht.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notifyObservers(nowPlaying: NowPlaying?) {
        nowPlayingObservers.forEach { it.onNowPlayingChanged(nowPlaying) }
    }

    /**
     * Bricht eine Quelle mitten im Titel weg, meldet ExoPlayer nach eigenen Wiederholungen einen
     * Fehler. Ein erneutes `prepare()` lädt den Titel von vorn an der aktuellen Position, und
     * [SongDataSource] kann dabei auf eine andere Quelle ausweichen. Einmal je Titel, damit es bei
     * ganz fehlendem Netz keine Endlosschleife gibt.
     */
    private class SourceErrorRecovery(private val player: Player) : Player.Listener {
        private var retriedItem: Int? = null

        override fun onPlayerError(error: PlaybackException) {
            val isSourceError = error.errorCode in IO_ERROR_CODES
            if (!isSourceError || retriedItem == player.currentMediaItemIndex) return
            retriedItem = player.currentMediaItemIndex
            Log.w(TAG, "Quelle ausgefallen, neuer Versuch", error)
            player.prepare()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retriedItem = null
        }

        private companion object {
            const val TAG = "PlaybackService"
            val IO_ERROR_CODES = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        }
    }

    /**
     * Während der Wiedergabe hält Media3 die CPU wach; mit [C.WAKE_MODE_NETWORK] zusätzlich das WLAN (High-Perf-Lock), das nur
     * Streams brauchen. Die Uhr streamt nie, Bücher und Folgen aus Dateien auch nicht. Songs (`earloft://song/…`) werden erst
     * beim Laden aufgelöst und können Streams sein; dafür bleibt es beim Netz-Modus.
     */
    private class WakeModeListener(private val player: ExoPlayer, private val allowStreaming: Boolean) : Player.Listener {
        private var currentMode = C.WAKE_MODE_LOCAL

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val mode = if (allowStreaming && !allLocal()) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
            if (mode != currentMode) {
                currentMode = mode
                player.setWakeMode(mode)
            }
        }

        private fun allLocal(): Boolean = (0 until player.mediaItemCount).all { index ->
            player.getMediaItemAt(index).localConfiguration?.uri?.scheme in LOCAL_SCHEMES
        }

        private companion object {
            val LOCAL_SCHEMES = setOf("file", "content")
        }
    }

    /**
     * Bücher und Folgen als Sprache: Bei kurzem Fokusverlust (Navigationsansage) pausiert ExoPlayer dann, statt nur leiser
     * zu werden; bei Sprache ginge sonst Inhalt verloren. Musik wird weiter nur leiser.
     */
    private class ContentTypeListener(private val player: ExoPlayer) : Player.Listener {
        private var currentType = C.AUDIO_CONTENT_TYPE_MUSIC

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val type = if (mediaItem?.isSpoken == true) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC
            if (type != currentType) {
                currentType = type
                player.setAudioAttributes(audioAttributes(type), /* handleAudioFocus = */ true)
            }
        }
    }

    /** Hält Warteschlange, Position und Beobachter (Widget) aktuell. */
    private inner class PersistenceListener(private val player: Player) : Player.Listener {

        // Hörbücher haben ihren Fortschritt in der Datenbank (BookPlaybackListener); die gespeicherte Musik-Warteschlange
        // darf dadurch weder geleert noch auf ihre Position zurückgesetzt werden. Ebenso wenig durch die kurz leere
        // Warteschlange beim Wechsel aufs Cast-Gerät.
        private val holdsSpoken: Boolean get() = player.currentMediaItem?.isSpoken == true || player.awaitsRemoteQueue

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !holdsSpoken) {
                queuePersistence.saveQueueDebounced(player, applicationScope)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) queuePersistence.saveLastBook(mediaItem.bookId, applicationScope)
            if (!holdsSpoken) queuePersistence.savePosition(player, applicationScope)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && !holdsSpoken) queuePersistence.savePosition(player, applicationScope)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!holdsSpoken) queuePersistence.savePosition(player, applicationScope)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (!holdsSpoken) queuePersistence.savePosition(player, applicationScope)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // Bis der Empfänger die Warteschlange meldet, zeigen Widget und Co. weiter den letzten Titel. Kommt stattdessen
            // nichts zurück aufs Handy (eine andere App hat den Empfänger übernommen), meldet der Gerätewechsel den Stand.
            if (player.awaitsRemoteQueue) return
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_DEVICE_INFO_CHANGED,
                )
            ) {
                val item = player.currentMediaItem
                val songId = item?.songId
                val bookId = item?.bookId
                notifyObservers(
                    if (item != null && (songId != null || bookId != null)) {
                        NowPlaying(
                            songId = songId,
                            title = item.mediaMetadata.title?.toString().orEmpty(),
                            artist = item.mediaMetadata.artist?.toString().orEmpty(),
                            albumId = if (bookId == null) item.mediaMetadata.albumIdExtra else null,
                            isPlaying = player.isPlaying,
                            bookId = bookId,
                            isEpisode = bookId != null && item.isEpisode,
                        )
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Ordner im Browse-Tree, deren Inhalt von der letzten Wiedergabe abhängt. */
private val CONTENT_FOLDERS = listOf(
    MediaIds.HOME,
    MediaIds.RECENT,
    MediaIds.EPISODES_CONTINUE,
    MediaIds.BOOKS_CONTINUE,
)

private fun audioAttributes(contentType: @C.AudioContentType Int): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(contentType)
        .build()
