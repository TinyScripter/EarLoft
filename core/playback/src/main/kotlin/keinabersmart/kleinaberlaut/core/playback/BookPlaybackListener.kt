package keinabersmart.kleinaberlaut.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import keinabersmart.kleinaberlaut.core.data.spoken.AbsSessionReporter
import keinabersmart.kleinaberlaut.core.data.spoken.BookProgressSync
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.data.spoken.ResumeRule
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenSettings
import keinabersmart.kleinaberlaut.core.data.spoken.resumePosition
import keinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alles, was nur für Hörbücher gilt (Konzept 12.3/12.4): Fortschritt festhalten und melden, Geschwindigkeit je Buch
 * setzen und merken. Läuft im Service auf dem Player-Thread; [scope] ist der Main-Scope.
 */
@OptIn(UnstableApi::class)
internal class BookPlaybackListener(
    private val player: Player,
    private val books: BookRepository,
    private val progressSync: BookProgressSync,
    settings: SpokenSettings,
    private val stats: StatsRepository,
    private val sessions: AbsSessionReporter,
    private val scope: CoroutineScope,
    /** Eine Podcast-Folge ist bis zum Ende gelaufen und der Player wartet (Parameter: Buch-ID der Folge). */
    private val onEpisodeEnded: (Long) -> Unit,
) : Player.Listener {

    private var timelineBookId: Long? = null
    private var timeline: BookTimeline? = null
    private var loop: Job? = null

    // Gehörte Zeit des laufenden Buchs bzw. der laufenden Folge (Statistik); [meterBookId] gehört die gemessene Zeit.
    private val meter = ListeningMeter()
    private var meterBookId: Long? = null
    private var meterIsEpisode = false

    // Einstellungen zum Fortsetzen; mitgeführt, damit das Zurückspulen beim Drücken von Play ohne Verzögerung greift.
    private var bookRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S)
    private var podcastRule = bookRule
    private var restartChapter = false

    /** Wann und in welchem Buch der Nutzer die Wiedergabe zuletzt angehalten hat. */
    private var pausedBookId: Long? = null
    private var pausedAt = 0L

    /** Pausiert wiederhergestellt ([restoredPaused]), aber [onMediaItemTransition] kam noch nicht: Buch und Pausenbeginn. */
    private var pendingRestore: Pair<Long, Long>? = null

    /**
     * Bücher, die seit dem Laden gespielt oder gespult wurden. Nur deren Stand wird festgehalten: Ein nur geladenes Buch
     * schriebe sonst beim Wechsel oder Beenden seine alte Position mit neuem Zeitstempel und überschriebe damit einen
     * neueren Stand eines anderen Geräts.
     */
    private val touched = mutableSetOf<Long>()

    init {
        scope.launch { settings.bookResume.collect { bookRule = it } }
        scope.launch { settings.podcastResume.collect { podcastRule = it } }
        scope.launch { settings.restartChapter.collect { restartChapter = it } }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncMeter()
        val bookId = mediaItem?.bookId
        if (bookId == null) {
            // Musik läuft immer mit normaler Geschwindigkeit.
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            return
        }
        // Innerhalb eines Buchs (nächste Datei) bleibt alles; bei einem anderen Buch gilt dessen Geschwindigkeit.
        if (timelineBookId == bookId) return
        timelineBookId = bookId
        // Ein frisch geladenes Buch hat seine Startposition schon aus dem gespeicherten Stand bekommen – außer es wurde
        // pausiert wiederhergestellt; dann zählt die Pause seit dem gespeicherten Stand.
        val restored = pendingRestore?.takeIf { it.first == bookId }
        pendingRestore = null
        pausedBookId = restored?.first
        pausedAt = restored?.second ?: 0L
        timeline = null
        scope.launch {
            timeline = books.timeline(bookId)
            books.progressNow(bookId)?.speed?.let { speed ->
                if (speed != player.playbackParameters.speed && player.currentMediaItem?.bookId == bookId) player.setPlaybackSpeed(speed)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val item = player.currentMediaItem
        val bookId = item?.bookId ?: return
        if (!playWhenReady) {
            pausedBookId = bookId
            pausedAt = System.currentTimeMillis()
        } else if (pausedBookId == bookId) {
            pausedBookId = null
            rewindAfterPause(item, bookId)
        }
    }

    /**
     * Das Buch [bookId] wurde pausiert wiederhergestellt (Start des Service); [pausedSinceMs] ist der Zeitpunkt des
     * gespeicherten Stands. Beim ersten Play gilt dann die Regel zum Fortsetzen wie nach einer Pause im Player.
     */
    fun restoredPaused(bookId: Long, pausedSinceMs: Long) {
        if (timelineBookId == bookId) {
            pausedBookId = bookId
            pausedAt = pausedSinceMs
        } else {
            pendingRestore = bookId to pausedSinceMs
        }
    }

    /** Wiedergabe nach einer Pause im selben Buch: nach langer Pause ein Stück zurück, ggf. an den Kapitelanfang. */
    private fun rewindAfterPause(item: MediaItem, bookId: Long) {
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: return
        val episode = item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
        val current = timeline.toBookPosition(item.bookTrackIndex ?: 0, player.currentPosition)
        val target = resumePosition(
            progress = BookProgress(current, timeline.durationMs, finished = false, updatedAt = pausedAt),
            nowMs = System.currentTimeMillis(),
            rule = if (episode) podcastRule else bookRule,
            chapters = if (episode) emptyList() else timeline.chapters,
            restartChapter = !episode && restartChapter,
        )
        if (target == current) return
        val located = timeline.locate(target)
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).bookTrackIndex == located.trackIndex } ?: return
        player.seekTo(index, located.offsetMs)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        meter.setSpeed(playbackParameters.speed)
        val bookId = player.currentMediaItem?.bookId ?: return
        scope.launch { books.setSpeed(bookId, playbackParameters.speed) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            newPosition.mediaItem?.bookId?.let { touched += it }
        }
        // Ein anderes Buch (oder Musik) beginnt: den Stand des bisherigen festhalten.
        val oldBookId = oldPosition.mediaItem?.bookId ?: return
        if (oldBookId == newPosition.mediaItem?.bookId) return
        record(oldBookId, oldPosition.mediaItem?.bookTrackIndex ?: 0, oldPosition.positionMs, force = true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncMeter()
        val item = player.currentMediaItem
        val bookId = item?.bookId
        loop?.cancel()
        if (bookId == null) return
        if (isPlaying) {
            touched += bookId
            loop = scope.launch {
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    recordCurrent(force = false)
                    flushListening(force = false)
                }
            }
        } else {
            recordCurrent(force = true)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            recordCurrent(force = true)
            val item = player.currentMediaItem
            val bookId = item?.bookId
            if (bookId != null && item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE && player.playWhenReady) {
                onEpisodeEnded(bookId)
            }
        }
        if (playbackState == Player.STATE_READY) learnDuration()
    }

    /** Folgen ohne (oder mit falscher) Dauer im Feed: die Datei sagt, wie lang sie wirklich ist. */
    private fun learnDuration() {
        val bookId = player.currentMediaItem?.bookId ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        scope.launch {
            if (books.learnEpisodeDuration(bookId, duration) && timelineBookId == bookId) timeline = books.timeline(bookId)
        }
    }

    /** Beim Beenden des Service aufrufen. */
    fun finish() {
        loop?.cancel()
        recordCurrent(force = true)
        flushListening(force = true)
        meter.stop()
    }

    /**
     * Die Zeit, die seit dem letzten Mal an [meterBookId] gehört wurde, in die Statistik schreiben; bei Büchern auch als
     * Sitzung an den Server melden ([force]: sofort statt gedrosselt).
     */
    private fun flushListening(force: Boolean = true) {
        val bookId = meterBookId ?: return
        val ms = meter.drain()
        if (ms <= 0) return
        if (meterIsEpisode) {
            scope.launch { stats.recordEpisodeListening(bookId, ms) }
            return
        }
        // Die Position kommt aus dem Player, solange er noch dieses Buch spielt; sonst bleibt die bisherige.
        val item = player.currentMediaItem
        val timeline = timeline.takeIf { timelineBookId == bookId && item?.bookId == bookId }
        val position = timeline?.toBookPosition(item?.bookTrackIndex ?: 0, player.currentPosition)
        val duration = timeline?.durationMs
        scope.launch {
            stats.recordBookListening(bookId, ms)
            sessions.record(bookId, ms, position, duration, force)
        }
    }

    /** Richtet die Messung auf das aktuelle Buch bzw. die aktuelle Folge und den Abspielzustand aus; die bisher gemessene Zeit wird zuerst verbucht. */
    private fun syncMeter() {
        flushListening()
        val item = player.currentMediaItem
        val bookId = item?.bookId
        meterBookId = bookId
        meterIsEpisode = item?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
        if (bookId != null && player.isPlaying) meter.start(player.playbackParameters.speed) else meter.stop()
    }

    private fun recordCurrent(force: Boolean) {
        val item = player.currentMediaItem ?: return
        val bookId = item.bookId ?: return
        record(bookId, item.bookTrackIndex ?: 0, player.currentPosition, force)
    }

    private fun record(bookId: Long, trackIndex: Int, positionInTrackMs: Long, force: Boolean) {
        if (bookId !in touched) return
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: return
        val position = timeline.toBookPosition(trackIndex, positionInTrackMs)
        // Ohne bekannte Dauer (Folge, deren Länge noch nicht gelernt ist) kann es nicht zu Ende sein.
        val finished = (timeline.durationMs > 0 && position >= timeline.durationMs - FINISHED_TOLERANCE_MS) ||
            (player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.bookId == bookId)
        scope.launch { progressSync.record(bookId, position, timeline.durationMs, finished, force) }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L

        /** So kurz vor dem Ende gilt ein Buch als zu Ende gehört (Abspann). */
        const val FINISHED_TOLERANCE_MS = 5_000L
    }
}
