package keinabersmart.kleinaberlaut.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackSourceResolver
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Wiedergabe-Service mit Media3-Session (Konzept 7.1). */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var sourceResolver: PlaybackSourceResolver
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var artworkRepository: ArtworkRepository
    @Inject lateinit var playbackStateRepository: PlaybackStateRepository
    @Inject lateinit var queuePersistence: QueuePersistence
    @Inject lateinit var nowPlayingObservers: Set<@JvmSuppressWildcards NowPlayingObserver>
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var streamCache: SimpleCache

    /** Für Player-Zugriffe (Main-Thread). */
    private val mainScope = MainScope()

    /** Für Datenbankarbeit, die das Beenden des Service überleben darf. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var session: MediaLibrarySession? = null
    private lateinit var playTracker: PlayTracker

    override fun onCreate() {
        super.onCreate()
        // kal://song/<id> → Datei, content:// oder Stream (Konzept 7.2). Streams laufen über den
        // gemeinsamen OkHttpClient (Auth) und einen LRU-Cache, dessen Schlüssel die sourceUid ist.
        val streamFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, streamFactory),
            SongDataSourceResolver(sourceResolver),
        )
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        playTracker = PlayTracker(player, playbackStateRepository, ioScope)
        player.addListener(playTracker)
        player.addListener(PersistenceListener(player))

        session = MediaLibrarySession.Builder(
            this,
            player,
            LibrarySessionCallback(this, libraryRepository, queuePersistence, mainScope),
        )
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this, artworkRepository, mainScope)))
            .apply { sessionActivity()?.let { setSessionActivity(it) } }
            .build()

        restoreQueue(player)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        session?.let { session ->
            playTracker.finishCurrent()
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

    /** Hält Warteschlange, Position und Beobachter (Widget) aktuell. */
    private inner class PersistenceListener(private val player: Player) : Player.Listener {

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                queuePersistence.saveQueueDebounced(player, ioScope)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            queuePersistence.savePosition(player, ioScope)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) queuePersistence.savePosition(player, ioScope)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            queuePersistence.savePosition(player, ioScope)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            queuePersistence.savePosition(player, ioScope)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                val item = player.currentMediaItem
                val songId = item?.songId
                notifyObservers(
                    songId?.let {
                        NowPlaying(
                            songId = it,
                            title = item.mediaMetadata.title?.toString().orEmpty(),
                            artist = item.mediaMetadata.artist?.toString().orEmpty(),
                            albumId = item.mediaMetadata.albumIdExtra,
                            isPlaying = player.isPlaying,
                        )
                    },
                )
            }
        }
    }
}
