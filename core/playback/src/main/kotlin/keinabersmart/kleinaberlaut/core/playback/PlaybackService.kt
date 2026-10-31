package keinabersmart.kleinaberlaut.core.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackPolicy
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackSourceResolver
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackStateRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.core.data.spoken.BookPlaybackSource
import keinabersmart.kleinaberlaut.core.data.spoken.BookProgressSync
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Optional
import javax.inject.Inject

/**
 * Extra am Start-Intent der Sitzungs-Aktivität (Tipp auf Benachrichtigung bzw. auf den Chip der laufenden Musik auf
 * dem Zifferblatt): Die App soll direkt die Wiedergabe-Ansicht öffnen. Wer sie nicht kennt, startet normal.
 */
const val EXTRA_OPEN_NOW_PLAYING = "keinabersmart.kleinaberlaut.OPEN_NOW_PLAYING"

/** Wiedergabe-Service mit Media3-Session (Konzept 7.1). */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var sourceResolver: PlaybackSourceResolver
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var playlistRepository: PlaylistRepository
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
    @Inject lateinit var bookPlaybackSource: BookPlaybackSource
    @Inject lateinit var podcastRepository: PodcastRepository
    @Inject lateinit var bookCovers: BookCovers

    /** Für Player-Zugriffe (Main-Thread). */
    private val mainScope = MainScope()

    /** Für Datenbankarbeit, die das Beenden des Service überleben darf. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var session: MediaLibrarySession? = null
    private lateinit var playTracker: PlayTracker
    private lateinit var bookListener: BookPlaybackListener
    private val replayGain = ReplayGainProcessor()

    override fun onCreate() {
        super.onCreate()
        // kal://song/<id> → Datei, content:// oder Stream (Konzept 7.2). Streams laufen über den
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
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Uhr: nicht über den Lautsprecher; Wear OS bietet stattdessen die Bluetooth-Ausgabe an (Wear-Konzept 9).
            .setSuppressPlaybackOnUnsuitableOutput(playbackPolicy.requireSuitableOutput)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        localPlayer.addListener(SourceErrorRecovery(localPlayer))
        localPlayer.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                replayGain.shuffleEnabled = shuffleModeEnabled
            }
        })

        // Handy: Cast-fähiger Player um den ExoPlayer; Hörverlauf und Warteschlange folgen dem jeweils aktiven Player.
        val factory = sessionPlayerFactory.orElse(null)
        val player = factory?.create(this, localPlayer) ?: localPlayer
        sleepTimer.attach(player.asSleepTimerTarget())
        playTracker = PlayTracker(player, playbackStateRepository, ioScope)
        player.addListener(playTracker)
        player.addListener(PersistenceListener(player))
        bookListener = BookPlaybackListener(player, bookRepository, bookProgressSync, mainScope) { endedId ->
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

        session = MediaLibrarySession.Builder(
            this,
            // Bei Hörbüchern gelten andere Sprungweiten (-30 s / +10 s), siehe SpokenAwarePlayer.
            SpokenAwarePlayer(player),
            LibrarySessionCallback(this, libraryRepository, playlistRepository, favoritesRepository, queuePersistence, factory, mainScope),
        )
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this, artworkRepository, bookCovers, mainScope)))
            .apply { sessionActivity()?.let { setSessionActivity(it) } }
            .build()

        session?.let { bindFavoriteButton(player, it) }
        restoreQueue(player)
    }

    /**
     * Folge-für-Folge (Konzept 12.4): Ist eine Podcast-Folge zu Ende, wird die nächste erst jetzt geladen. Wurde in der
     * Zwischenzeit etwas anderes gestartet, bleibt es dabei.
     */
    private fun continueWithNextEpisode(player: Player, endedId: Long) {
        mainScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                podcastRepository.autoNextAfter(endedId)?.let { bookPlaybackSource.prepare(it) }
            } ?: return@launch
            val stillWaiting = player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.bookId == endedId
            if (stillWaiting) player.playBook(prepared)
        }
    }

    /** Herz neben den Wiedergabetasten (Android Auto, Benachrichtigung); zeigt den Favoriten-Zustand des aktuellen Songs. */
    private fun bindFavoriteButton(player: Player, session: MediaLibrarySession) {
        var favoriteJob: Job? = null
        fun update() {
            favoriteJob?.cancel()
            val songId = player.currentMediaItem?.songId
            if (player.currentMediaItem?.isSpoken == true) {
                // Hörbuch: Springen statt Favorit.
                session.setCustomLayout(
                    listOf(
                        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
                            .setDisplayName(getString(R.string.spoken_seek_back))
                            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                            .setDisplayName(getString(R.string.spoken_seek_forward))
                            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                            .build(),
                    ),
                )
                return
            }
            if (songId == null) {
                session.setCustomLayout(emptyList())
                return
            }
            favoriteJob = mainScope.launch {
                favoritesRepository.isFavorite(songId).collect { favorite ->
                    val button = CommandButton.Builder(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                        .setDisplayName(getString(if (favorite) R.string.favorite_remove else R.string.favorite_add))
                        .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                        .build()
                    session.setCustomLayout(listOf(button))
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = update()
        })
        update()
    }

    /**
     * Lädt die letzte Warteschlange pausiert, damit die App nach einem Neustart dort weitermacht.
     * Hat inzwischen jemand anderes etwas geladen (z. B. per [LibrarySessionCallback.onPlaybackResumption]),
     * bleibt es dabei.
     */
    private fun restoreQueue(player: Player) {
        mainScope.launch {
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
        sleepTimer.detach()
        session?.let { session ->
            playTracker.finishCurrent()
            bookListener.finish()
            queuePersistence.savePosition(session.player, ioScope)
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

    /** Hält Warteschlange, Position und Beobachter (Widget) aktuell. */
    private inner class PersistenceListener(private val player: Player) : Player.Listener {

        // Hörbücher haben ihren Fortschritt in der Datenbank (BookPlaybackListener); die gespeicherte Musik-Warteschlange
        // darf dadurch weder geleert noch auf ihre Position zurückgesetzt werden.
        private val holdsSpoken: Boolean get() = player.currentMediaItem?.isSpoken == true

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !holdsSpoken) {
                queuePersistence.saveQueueDebounced(player, ioScope)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!holdsSpoken) queuePersistence.savePosition(player, ioScope)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && !holdsSpoken) queuePersistence.savePosition(player, ioScope)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!holdsSpoken) queuePersistence.savePosition(player, ioScope)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (!holdsSpoken) queuePersistence.savePosition(player, ioScope)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) {
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
                        )
                    } else {
                        null
                    },
                )
            }
        }
    }
}
