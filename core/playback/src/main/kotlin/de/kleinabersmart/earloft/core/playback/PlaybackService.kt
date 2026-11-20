package de.kleinabersmart.earloft.core.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewConfiguration
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
import androidx.media3.common.util.Util
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
import de.kleinabersmart.earloft.core.data.spoken.BookButtons
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.repository.MobileStreaming
import de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver
import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookPlaybackSource
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.di.LoggingExceptionHandler
import de.kleinabersmart.earloft.core.data.mix.MixRepository
import de.kleinabersmart.earloft.core.data.mix.MixSeed
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.model.AppUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Optional
import javax.inject.Inject
import de.kleinabersmart.earloft.core.network.podcast.PODCAST_USER_AGENT

/**
 * Extra on the start intent of the session activity (tap on the notification or on the now-playing chip on
 * the watch face): the app should open the now playing view directly. Whoever doesn't know it starts normally.
 */
const val EXTRA_OPEN_NOW_PLAYING = "de.kleinabersmart.earloft.OPEN_NOW_PLAYING"

/** Playback service with a Media3 session (concept 7.1). */
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
    @Inject lateinit var mobileStreaming: MobileStreaming
    @Inject lateinit var replayGainSettings: ReplayGainSettings
    @Inject lateinit var endlessMixSettings: EndlessMixSettings
    @Inject lateinit var mixRepository: MixRepository
    @Inject internal lateinit var libraryRemovals: LibraryRemovals
    @Inject lateinit var sessionPlayerFactory: Optional<SessionPlayerFactory>
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var bookRepository: BookRepository
    @Inject lateinit var bookProgressSync: BookProgressSync
    @Inject lateinit var spokenSettings: SpokenSettings
    @Inject lateinit var spokenEffects: SpokenEffectsRepository
    @Inject lateinit var statsRepository: StatsRepository
    @Inject lateinit var absSessionReporter: AbsSessionReporter
    @Inject lateinit var bookPlaybackSource: BookPlaybackSource
    @Inject lateinit var continueListening: ContinueListening
    @Inject lateinit var bookCovers: BookCovers
    @Inject lateinit var servicePlayer: ServicePlayer
    @Inject lateinit var playingSources: PlayingSources
    @Inject lateinit var audioEffectSession: AudioEffectSession
    @Inject internal lateinit var browseTree: BrowseTree

    /** For database work that may outlive the service (only suspending DataStore/Room calls). */
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    /** For player access (main thread). */
    private val mainScope = MainScope() + LoggingExceptionHandler
    private var restoreJob: Job? = null

    private var session: MediaLibrarySession? = null
    private lateinit var playTracker: PlayTracker
    private lateinit var bookListener: BookPlaybackListener
    private var sessionPlayer: SpokenAwarePlayer? = null
    private var shakeDetector: ShakeDetector? = null
    private var audioEffects: SpokenAudioEffects? = null

    /** Name of the current chapter for notification and widget; `null` without chapters. */
    private var chapterTitle: String? = null

    /** The loaded book or episode has chapters; together with [bookButtons] this determines the buttons ([bindFavoriteButton]). */
    private var hasChapters = false
    private var bookButtons = BookButtons.SEEK
    private var updateButtons: (() -> Unit)? = null
    private var streamPreloader: StreamPreloader? = null
    private val replayGain = ReplayGainProcessor()

    override fun onCreate() {
        super.onCreate()
        // earloft://song/<id> → file, content:// or stream (concept 7.2). Streams go through the
        // shared OkHttpClient (auth) and an LRU cache keyed by the sourceUid.
        val streamFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            // Own user agent: some podcast hosters reject OkHttp's with 403 (downloads already send it).
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient).setUserAgent(PODCAST_USER_AGENT))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = SongDataSource.Factory(
            DefaultDataSource.Factory(this, streamFactory),
            sourceResolver,
            SourceHealth(),
            getString(R.string.song_unavailable),
            replayGain::onSourceResolved,
            playingSources::record,
            streamCache::isFullyCached,
        )
        // The processor chain itself counts the time saved by skipping silence (statistics, SkippedSilence).
        val skippedSilence = SkippedSilence()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = ReplayGainAudioSink(
                SilenceRateAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessorChain(
                            CountingAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(replayGain), skippedSilence),
                        )
                        .build(),
                    skippedSilence,
                ),
                replayGain,
            )
        }
        val localPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes(C.AUDIO_CONTENT_TYPE_MUSIC), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Watch: not over the speaker; Wear OS offers the Bluetooth output instead (Wear concept 9).
            .setSuppressPlaybackOnUnsuitableOutput(playbackPolicy.requireSuitableOutput)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        localPlayer.addListener(SourceErrorRecovery(localPlayer))
        audioEffectSession.attach(this, localPlayer)
        val audioEffects = SpokenAudioEffects(localPlayer).also { this.audioEffects = it }
        // Watch: no streams, so nothing to preload.
        if (playbackPolicy.allowStreaming) {
            streamPreloader = StreamPreloader(
                localPlayer,
                streamFactory::createDataSourceForDownloading,
                sourceResolver::candidates,
                isMetered = mobileStreaming::isMetered,
                scope = mainScope,
                isFullyCached = streamCache::isFullyCached,
            ).also(localPlayer::addListener)
        }
        localPlayer.addListener(
            WakeModeListener(localPlayer, playbackPolicy.allowStreaming, mainScope) { songId ->
                sourceResolver.candidates(songId).firstOrNull()?.location is PlaybackLocation.LocalFile
            },
        )
        localPlayer.addListener(ContentTypeListener(localPlayer))
        localPlayer.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                replayGain.shuffleEnabled = shuffleModeEnabled
            }
        })

        // Phone: cast-capable player around the ExoPlayer; listening history and queue follow whichever player is active.
        val factory = sessionPlayerFactory.orElse(null)
        val player = factory?.create(this, localPlayer) ?: localPlayer
        playTracker = PlayTracker(player) { songId, playedAt, listenedMs ->
            applicationScope.launch { playbackStateRepository.recordPlay(songId, playedAt, listenedMs) }
        }
        player.addListener(playTracker)
        player.addListener(PersistenceListener(player))
        bindEndlessMix(player, factory)
        bookListener = BookPlaybackListener(
            player, bookRepository, bookProgressSync, spokenSettings, statsRepository, absSessionReporter, mainScope, applicationScope,
            onEpisodeEnded = { endedId -> continueWithNextEpisode(player, endedId) },
            onChapterChanged = { chapter ->
                chapterTitle = chapter?.let { it.title.ifBlank { getString(R.string.spoken_chapter_number, it.index + 1) } }
                sessionPlayer?.chapterTitle = chapterTitle
                if (!player.awaitsRemoteQueue) notifyObservers(nowPlayingOf(player))
                // Chapter buttons only if the book has chapters (M4); without chapters the seek buttons stay.
                if ((chapter != null) != hasChapters) {
                    hasChapters = chapter != null
                    updateButtons?.invoke()
                }
            },
            effects = spokenEffects,
            // A cast receiver plays without effects; the local player keeps them until playing locally again.
            applyAudio = audioEffects::apply,
            skippedSilenceMs = skippedSilence::drainMs,
        )
        player.addListener(bookListener)
        servicePlayer.attach(player) { bookId, playNext -> markFinished(player, bookId, playNext) }
        sleepTimer.attach(player.asSleepTimerTarget(spot = bookListener::sleepSpot, rewind = bookListener::rewindHeard))
        bindShakeToExtend(player)
        mainScope.launch {
            libraryRemovals.removals.collect { removeMissingSongs(player) }
        }
        mainScope.launch {
            combine(replayGainSettings.mode, replayGainSettings.preAmpDb, ::Pair)
                .collect { (mode, preAmpDb) -> replayGain.setSettings(mode, preAmpDb) }
        }

        // Books and episodes have their own configurable skip distances, see SpokenAwarePlayer.
        val sessionPlayer = SpokenAwarePlayer(player).also { this.sessionPlayer = it }
        sessionPlayer.chapterTitle = chapterTitle
        // Headphones: double click or "next" to the next chapter; for episodes without a further chapter to the next episode. The
        // chapter buttons in the notification and Auto make the same jumps (M4).
        val nextChapter = {
            if (!bookListener.skipChapter(forward = true)) player.currentMediaItem?.takeIf { it.isEpisode }?.bookId?.let { skipToNextEpisode(player, it) }
        }
        val previousChapter = { if (!bookListener.skipChapter(forward = false)) player.seekToPrevious() }
        val mediaButtons = SpokenMediaButtons(
            handler = Handler(Looper.getMainLooper()),
            doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
            isSpoken = { player.currentMediaItem?.isSpoken == true },
            playPause = { Util.handlePlayPauseButtonAction(player) },
            next = nextChapter,
            previous = previousChapter,
        )
        val callback = LibrarySessionCallback(
            this, libraryRepository, favoritesRepository, queuePersistence, bookPlaybackSource, browseTree, continueListening, factory, mainScope,
            mediaButtons = mediaButtons,
            onChapterButton = { forward -> if (forward) nextChapter() else previousChapter() },
        ) {
            restoreJob?.join()
        }
        // Before the first playback: the media card and lock screen load the cover via the CoverProvider.
        callback.grantSystemUiCoverAccess()
        session = MediaLibrarySession.Builder(this, sessionPlayer, callback)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this, artworkRepository, bookCovers, mainScope)))
            .apply { sessionActivity()?.let { setSessionActivity(it) } }
            .build()

        session?.let { bindFavoriteButton(sessionPlayer, it) }
        session?.let { notifyAreaChanges(it) }
        restoreQueue(player)
        session?.let { notifyContentChanges(it, player) }
    }

    /**
     * "Keep playing endlessly": a mix at the end of the queue ([EndlessMix]), on the active player (also while casting). Without a network
     * only from downloaded and local content, otherwise playback would end at the first unplayable title (review V9).
     */
    private fun bindEndlessMix(player: Player, factory: SessionPlayerFactory?) {
        var enabled = false
        val endlessMix = EndlessMix(
            player,
            { enabled },
            { songId -> mixRepository.mix(MixSeed.Song(songId), offlineOnly = !hasInternet()) },
            factory,
            mainScope,
        )
        player.addListener(endlessMix)
        mainScope.launch {
            endlessMixSettings.enabled.distinctUntilChanged().collect {
                enabled = it
                endlessMix.check()
            }
        }
    }

    /** A network with validated internet access (not merely connected, e.g. Wi-Fi without captive portal sign-in). */
    private fun hasInternet(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Areas toggled: the root tabs and "Recent" change. Android Auto then reloads them
     * (whether immediately or only on the next connection is up to Auto).
     */
    /**
     * "Recent" and the continue-listening folders change with what's playing. Android Auto remembers every answer until the next
     * notification: a query right after the service started (before the restored queue) would otherwise stay empty.
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
     * Episode by episode (concept 12.4): when a podcast episode ends, the next one is only loaded now. If something else was
     * started in the meantime, that stays.
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
                // Cast: the receiver stands still after the end and has usually already unloaded the episode.
                Player.STATE_IDLE -> player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE &&
                    player.currentMediaItem?.bookId.let { it == null || it == endedId }
                else -> false
            }
            if (stillWaiting) player.playBook(prepared)
        }
    }

    /**
     * Headphone button without a further chapter: the next episode of the queue, like "Next episode" in the player
     * (independent of auto-play); it plays if the previous one was playing.
     */
    private fun skipToNextEpisode(player: Player, currentId: Long, autoPlay: Boolean = player.playWhenReady, orElse: () -> Unit = {}) {
        mainScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                continueListening.next(currentId)?.let { next ->
                    continueListening.started(next)
                    bookPlaybackSource.prepare(next)
                }
            }
            // Something else was started in the meantime: keep it.
            if (player.currentMediaItem?.bookId != currentId) return@launch
            if (prepared != null) player.playBook(prepared, autoPlay = autoPlay) else orElse()
        }
    }

    /**
     * "Mark as played" for the loaded book or episode ([ServicePlayer.markFinished]): set the state to finished, then
     * pause, or with [playNext] play the next episode of the queue (pause as well if there is no next one).
     */
    private fun markFinished(player: Player, bookId: Long, playNext: Boolean): Boolean {
        if (!bookListener.markFinished(bookId)) return false
        if (playNext && player.currentMediaItem?.isEpisode == true) {
            skipToNextEpisode(player, bookId, autoPlay = true, orElse = player::pause)
        } else {
            player.pause()
        }
        return true
    }

    /**
     * Shaking extends the sleep timer ([SleepTimer.extend]): the sensor only runs while the timer allows it
     * (shortly before and after expiry), the setting is on and a book or episode is loaded. A short vibration
     * confirms it.
     */
    private fun bindShakeToExtend(player: Player) {
        if (!playbackPolicy.shakeToExtendSleepTimer) return
        val detector = ShakeDetector(this) {
            if (player.currentMediaItem?.isSpoken == true && sleepTimer.extend()) confirmShake()
        }.also { shakeDetector = it }
        mainScope.launch {
            combine(sleepTimer.shakeWindow, spokenSettings.shakeToExtend) { window, enabled -> window && enabled }
                .distinctUntilChanged()
                .collect { listen -> if (listen && player.currentMediaItem?.isSpoken == true) detector.start() else detector.stop() }
        }
    }

    private fun confirmShake() {
        @Suppress("DEPRECATION") // VibratorManager only from API 31; the default vibrator is enough for a short pulse.
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(SHAKE_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * Heart next to the playback buttons (Android Auto, notification); shows the favorite state of the current song.
     * For books and episodes the seek buttons instead of ⏮/⏭; the arrow without a number because the
     * skip distance is configurable and Media3 doesn't have an icon for each. Books with chapters optionally get
     * ([BookButtons.CHAPTERS], `books_improvements.md` M4) chapter back/forward as the session's own commands instead.
     *
     * Via `setMediaButtonPreferences`, not `setCustomLayout`: only then does Media3 turn buttons with a player command
     * (seeking) into custom actions of the platform session. Otherwise they are missing from the system's media controls and from Android
     * Auto, which only see that session.
     */
    private fun bindFavoriteButton(player: SpokenAwarePlayer, session: MediaLibrarySession) {
        var favoriteJob: Job? = null
        fun update() {
            favoriteJob?.cancel()
            val songId = player.currentMediaItem?.songId
            val steps = player.currentSteps
            val item = player.currentMediaItem
            if (steps != null && bookButtons == BookButtons.CHAPTERS && item?.isEpisode == false && hasChapters) {
                session.setMediaButtonPreferences(
                    listOf(
                        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                            .setDisplayName(getString(R.string.spoken_chapter_previous))
                            .setSessionCommand(SessionCommand(ACTION_CHAPTER_PREVIOUS, Bundle.EMPTY))
                            .setSlots(CommandButton.SLOT_BACK)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_NEXT)
                            .setDisplayName(getString(R.string.spoken_chapter_next))
                            .setSessionCommand(SessionCommand(ACTION_CHAPTER_NEXT, Bundle.EMPTY))
                            .setSlots(CommandButton.SLOT_FORWARD)
                            .build(),
                    ),
                )
                return
            }
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
            combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, spokenSettings.bookButtons, ::Triple).collect { (book, podcast, buttons) ->
                player.bookSteps = book
                player.podcastSteps = podcast
                bookButtons = buttons
                update()
            }
        }
        updateButtons = ::update
        update()
    }

    /**
     * Loads the last queue paused, so the app continues there after a restart.
     * If someone else has loaded something in the meantime (e.g. via [LibrarySessionCallback.onPlaybackResumption]),
     * that stays. Likewise if the app reattached to a running cast session on start: the
     * receiver only reports its queue shortly afterwards, and the saved one would otherwise replace it. If it was no longer
     * the same session, the app detaches again; then the saved queue does end up on the phone after all.
     */
    private fun restoreQueue(player: Player) {
        restoreJob = mainScope.launch {
            suspend fun taken() = !awaitLocal(player, REJOIN_WAIT_MS) || player.mediaItemCount > 0
            // If a book or episode played last, it is loaded (paused) instead of the music queue.
            val lastBookId = queuePersistence.lastBookId()
            if (lastBookId != null) {
                // Without rewinding and without writing: this start isn't necessarily followed by playback.
                val prepared = withContext(Dispatchers.IO) { bookPlaybackSource.restore(lastBookId) }
                if (taken()) return@launch
                if (prepared != null) {
                    player.playBook(prepared, autoPlay = false, prepare = false)
                    prepared.pausedSinceMs?.let { bookListener.restoredPaused(lastBookId, it) }
                    return@launch
                }
            }
            val restored = queuePersistence.restore() ?: return@launch
            if (taken()) return@launch
            player.shuffleModeEnabled = restored.shuffle
            player.repeatMode = restored.repeatMode
            player.setMediaItems(restored.items, restored.startIndex, restored.startPositionMs)
        }
    }

    /** Removes titles from the queue whose song no longer exists (e.g. server removed). */
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
        shakeDetector?.stop()
        audioEffects?.release()
        sleepTimer.detach()
        servicePlayer.detach()
        session?.let { session ->
            playTracker.finishCurrent()
            bookListener.finish()
            // With a book in the player, its file and position would otherwise end up in the music queue's state.
            if (session.player.currentMediaItem?.isSpoken != true) saveMusicPosition(session.player)
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
        // SINGLE_TOP: if the app is already open, the activity receives the intent via onNewIntent instead of losing it.
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
     * If a source drops out mid-title, ExoPlayer reports an error after its own
     * retries. A new `prepare()` reloads the title from the current position, and
     * [SongDataSource] can fall back to another source. Once per title, so that there is no endless loop
     * when the network is completely gone.
     */
    private class SourceErrorRecovery(private val player: Player) : Player.Listener {
        private var retriedItem: Int? = null

        override fun onPlayerError(error: PlaybackException) {
            val isSourceError = error.errorCode in IO_ERROR_CODES
            if (!isSourceError || retriedItem == player.currentMediaItemIndex) return
            retriedItem = player.currentMediaItemIndex
            Log.w(TAG, "Source failed, retrying", error)
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
     * During playback Media3 keeps the CPU awake; with [C.WAKE_MODE_NETWORK] also the Wi-Fi (high-perf lock), which only
     * streams need. The watch never streams, and neither do books and episodes from files. Songs (`earloft://song/…`) are only
     * resolved on load; for them [playsLocally] asks where the current and the next one (Media3 loads it ahead) would come
     * from – a downloaded or device album with the display off no longer holds the Wi-Fi for hours (review G9).
     */
    private class WakeModeListener(
        private val player: ExoPlayer,
        private val allowStreaming: Boolean,
        private val scope: CoroutineScope,
        private val playsLocally: suspend (songId: Long) -> Boolean,
    ) : Player.Listener {
        private var currentMode = C.WAKE_MODE_LOCAL
        private var check: Job? = null

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = update()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = update()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = update()

        override fun onRepeatModeChanged(repeatMode: Int) = update()

        private fun update() {
            check?.cancel()
            if (!allowStreaming) return apply(C.WAKE_MODE_LOCAL)
            val uris = listOf(player.currentMediaItemIndex, player.nextMediaItemIndex)
                .filter { it in 0 until player.mediaItemCount }
                .mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri }
            val songIds = uris.mapNotNull { AppUris.parseSongId(it.toString()) }
            val othersLocal = uris.filter { AppUris.parseSongId(it.toString()) == null }.all { it.scheme in LOCAL_SCHEMES }
            if (!othersLocal) return apply(C.WAKE_MODE_NETWORK)
            if (songIds.isEmpty()) return apply(C.WAKE_MODE_LOCAL)
            check = scope.launch {
                val local = withContext(Dispatchers.IO) { songIds.all { playsLocally(it) } }
                apply(if (local) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
            }
        }

        private fun apply(mode: Int) {
            if (mode != currentMode) {
                currentMode = mode
                player.setWakeMode(mode)
            }
        }

        private companion object {
            val LOCAL_SCHEMES = setOf("file", "content")
        }
    }

    /**
     * Books and episodes as speech: on short focus loss (navigation announcement) ExoPlayer then pauses instead of just getting
     * quieter; with speech, content would otherwise be lost. Music still only gets quieter.
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

    /**
     * Saves the music position; while casting only song and position (looked up in the saved queue), because the
     * cast player only holds a window of the queue and knows no shuffle. Only call when music is in the player.
     */
    private fun saveMusicPosition(player: Player) {
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            val songId = player.currentMediaItem?.songId ?: return
            queuePersistence.saveRemotePosition(songId, player.currentPosition, applicationScope)
        } else {
            queuePersistence.savePosition(player, applicationScope)
        }
    }

    /** Keeps queue, position and observers (widget) up to date. */
    private inner class PersistenceListener(private val player: Player) : Player.Listener {

        // Audiobooks keep their progress in the database (BookPlaybackListener); the saved music queue
        // must neither be cleared nor reset to its position by them. Nor by the briefly empty
        // queue when switching to the cast device.
        private val holdsSpoken: Boolean get() = player.currentMediaItem?.isSpoken == true || player.awaitsRemoteQueue

        // While casting, the player only holds a window of the queue and knows no shuffle (Media3 `RemoteCastPlayer`);
        // the phone's state stays saved, see [QueuePersistence.saveRemotePosition].
        private val isRemote: Boolean get() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !holdsSpoken && !isRemote) {
                queuePersistence.saveQueueDebounced(player, applicationScope)
            }
        }

        /**
         * While casting: song and position replaced by a new queue ([onPositionDiscontinuity]). The receiver first reports
         * an empty queue and then the new one; what it was only shows with the following title change.
         */
        private var replacedRemoteSong: Pair<Long, Long>? = null

        /**
         * The last reported playback state was `STATE_ENDED`. When a book replaces music that played to the end, the player has
         * already left that state in [onPositionDiscontinuity], and the old position is the song's end.
         */
        private var ended = false

        override fun onPlaybackStateChanged(playbackState: Int) {
            ended = playbackState == Player.STATE_ENDED
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Books and episodes play without shuffle and repeat, however they were started (app, Android Auto, Assistant);
            // only now, so the music's modes were saved before ([onPositionDiscontinuity]).
            if (mediaItem?.isSpoken == true && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
                if (player.repeatMode != Player.REPEAT_MODE_OFF) player.repeatMode = Player.REPEAT_MODE_OFF
            }
            if (mediaItem != null) {
                queuePersistence.saveLastBook(mediaItem.bookId, applicationScope)
                replacedRemoteSong?.let { (songId, position) ->
                    if (mediaItem.isSpoken) queuePersistence.saveRemotePosition(songId, position, applicationScope)
                }
                replacedRemoteSong = null
            }
            if (!holdsSpoken) saveMusicPosition(player)
        }

        /**
         * A book or episode replaces playing music (`setMediaItems`): afterwards the player already holds the spoken content, and only
         * [oldPosition] knows the song's position. Without this, "Resume music" started the song from the beginning or at the
         * last saved position. Shuffle and repeat are still the music's here (`playBook` sets them afterwards).
         */
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_REMOVE) return
            val songId = oldPosition.mediaItem?.songId ?: return
            if (isRemote) {
                // While casting, the new entry is still empty here (observed on the Chromecast); saving happens on the title change.
                replacedRemoteSong = songId to oldPosition.positionMs
            } else if (newPosition.mediaItem?.isSpoken == true) {
                // Played to the end: like [QueuePersistence.savePosition], otherwise "Resume music" would end again immediately.
                queuePersistence.saveMusicPosition(
                    oldPosition.mediaItemIndex,
                    if (ended) 0L else oldPosition.positionMs,
                    player.shuffleModeEnabled,
                    player.repeatMode,
                    applicationScope,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && !holdsSpoken) saveMusicPosition(player)
        }

        // While casting, the player reports "shuffle off" on connect (it knows none); that's not the music's mode.
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!holdsSpoken && !isRemote) queuePersistence.savePosition(player, applicationScope)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (!holdsSpoken && !isRemote) queuePersistence.savePosition(player, applicationScope)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // Until the receiver reports the queue, the widget and co. keep showing the last title. If instead
            // nothing comes back to the phone (another app took over the receiver), the device switch reports the state.
            if (player.awaitsRemoteQueue) return
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_DEVICE_INFO_CHANGED,
                )
            ) {
                notifyObservers(nowPlayingOf(player))
            }
        }
    }

    private fun nowPlayingOf(player: Player): NowPlaying? {
        val item = player.currentMediaItem ?: return null
        val songId = item.songId
        val bookId = item.bookId
        if (songId == null && bookId == null) return null
        return NowPlaying(
            songId = songId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            albumId = if (bookId == null) item.mediaMetadata.albumIdExtra else null,
            isPlaying = player.isPlaying,
            bookId = bookId,
            isEpisode = bookId != null && item.isEpisode,
            chapter = chapterTitle.takeIf { bookId != null },
        )
    }
}

private const val SHAKE_VIBRATION_MS = 40L

/** Reattaching to a cast session after start can take this long (discovery and connecting). */
private const val REJOIN_WAIT_MS = 45_000L

/** Main thread. Waits at most [timeoutMs] until [player] plays on the device again; `false` if it stays cast. */
private suspend fun awaitLocal(player: Player, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
    callbackFlow {
        val listener = object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                trySend(deviceInfo.playbackType)
            }
        }
        player.addListener(listener)
        send(player.deviceInfo.playbackType)
        awaitClose { player.removeListener(listener) }
    }.first { it != DeviceInfo.PLAYBACK_TYPE_REMOTE }
    true
} ?: false

/** Folders in the browse tree whose content depends on the last playback. */
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
