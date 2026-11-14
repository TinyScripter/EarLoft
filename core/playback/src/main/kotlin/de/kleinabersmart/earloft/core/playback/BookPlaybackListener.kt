package de.kleinabersmart.earloft.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.ResumeRule
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.spoken.resumePosition
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.model.sameSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alles, was nur für Hörbücher gilt (Konzept 12.3/12.4): Fortschritt festhalten und melden, Tempo und Effekte nach der
 * Rangfolge Buch bzw. Folge > Abo > Bereich setzen ([SpokenEffectsRepository]; gespeichert wird dort, wo der Nutzer sie
 * ändert), Outro überspringen. Läuft im Service auf dem Player-Thread; [scope] ist der Main-Scope, [persistScope] der App-Scope
 * für das Schreiben – es muss das Ende des Service überleben (der Main-Scope endet in `onDestroy` gleich nach [finish]).
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
    private val persistScope: CoroutineScope,
    /** Eine Podcast-Folge ist bis zum Ende gelaufen und der Player wartet (Parameter: Buch-ID der Folge). */
    private val onEpisodeEnded: (Long) -> Unit,
    /** Das laufende Kapitel hat gewechselt; `null` bei Musik, ohne Kapitel und solange die Zeitachse lädt. */
    private val onChapterChanged: (BookChapter?) -> Unit = {},
    private val effects: SpokenEffectsRepository,
    /** Stille kürzen und Lautstärke anheben am lokalen Player; bei Musik `(false, 0)`. */
    private val applyAudio: (skipSilence: Boolean, volumeBoostDb: Int) -> Unit = { _, _ -> },
) : Player.Listener {

    private var timelineBookId: Long? = null
    private var timeline: BookTimeline? = null
    private var loop: Job? = null

    /** Zuletzt gemeldetes Kapitel (mit Buch) und der Wecker für den nächsten Kapitelwechsel. */
    private var reportedChapter: Pair<Long, BookChapter>? = null
    private var chapterJob: Job? = null

    /** Was für das geladene Buch bzw. die Folge gilt ([timelineBookId]), der Beobachter dazu und der Wecker fürs Outro. */
    private var currentEffects: SpokenEffects? = null
    private var effectsJob: Job? = null
    private var outroJob: Job? = null

    // Gehörte Zeit des laufenden Buchs bzw. der laufenden Folge (Statistik); [meterBookId] gehört die gemessene Zeit.
    private val meter = ListeningMeter()
    private var meterBookId: Long? = null
    private var meterIsEpisode = false

    /** Durch „Stille kürzen“ gesparte Zeit (Wanduhr) an [meterBookId] seit dem letzten Verbuchen. */
    private var savedPending = 0.0

    // Einstellungen zum Fortsetzen; mitgeführt, damit das Zurückspulen beim Drücken von Play ohne Verzögerung greift.
    private var bookRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S)
    private var podcastRule = bookRule
    private var restartChapter = false

    /** Wann und in welchem Buch der Nutzer die Wiedergabe zuletzt angehalten hat. */
    private var pausedBookId: Long? = null
    private var pausedAt = 0L

    /** Play kam, bevor die Zeitachse des Buchs geladen war (Kaltstart per Kopfhörer): die Regel danach anwenden. */
    private var rewindWhenLoaded: Long? = null

    /** Pausiert wiederhergestellt ([restoredPaused]), aber [onMediaItemTransition] kam noch nicht: Buch und Pausenbeginn. */
    private var pendingRestore: Pair<Long, Long>? = null

    /**
     * Bücher, die seit dem Laden gespielt oder gespult wurden. Nur deren Stand wird festgehalten: Ein nur geladenes Buch
     * schriebe sonst beim Wechsel oder Beenden seine alte Position mit neuem Zeitstempel und überschriebe damit einen
     * neueren Stand eines anderen Geräts.
     */
    private val touched = mutableSetOf<Long>()

    /** Cast meldet kein `STATE_ENDED`; die Wache erkennt das Ende am Stillstand des Empfängers. */
    private val remoteEnd = RemoteEndWatch()

    /** Die Warteschlange ging gerade an den Cast-Empfänger ([awaitsRemoteQueue]); der lädt sie mit normaler Geschwindigkeit. */
    private var handingOver = false

    init {
        scope.launch { settings.bookResume.collect { bookRule = it } }
        scope.launch { settings.podcastResume.collect { podcastRule = it } }
        scope.launch { settings.restartChapter.collect { restartChapter = it } }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncMeter()
        val bookId = mediaItem?.bookId
        val handedOver = handingOver
        handingOver = false
        if (bookId == null) {
            if (player.awaitsRemoteQueue) {
                handingOver = true
                return
            }
            // Musik läuft immer mit normaler Geschwindigkeit und ohne Effekte.
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            effectsJob?.cancel()
            currentEffects = null
            applyAudio(false, 0)
            updateTimers()
            return
        }
        // Innerhalb eines Buchs (nächste Datei) bleibt alles; bei einem anderen Buch gilt dessen Geschwindigkeit.
        if (timelineBookId == bookId) {
            // Zurück aus Musik ins selbe Buch: Die Musik hat die Effekte abgeschaltet und ihren Beobachter beendet.
            if (effectsJob?.isActive != true) followEffects(bookId)
            // Der Cast-Empfänger lädt die Warteschlange mit normaler Geschwindigkeit.
            if (handedOver) currentEffects?.speed?.takeUnless { sameSpeed(it, player.playbackParameters.speed) }?.let(player::setPlaybackSpeed)
            updateTimers()
            return
        }
        timelineBookId = bookId
        followEffects(bookId)
        // Ein frisch geladenes Buch hat seine Startposition schon aus dem gespeicherten Stand bekommen – außer es wurde
        // pausiert wiederhergestellt; dann zählt die Pause seit dem gespeicherten Stand.
        val restored = pendingRestore?.takeIf { it.first == bookId }
        pendingRestore = null
        pausedBookId = restored?.first
        pausedAt = restored?.second ?: 0L
        timeline = null
        updateTimers()
        scope.launch {
            timeline = books.timeline(bookId)
            updateTimers()
            if (rewindWhenLoaded == bookId) {
                rewindWhenLoaded = null
                player.currentMediaItem?.takeIf { it.bookId == bookId }?.let { rewindAfterPause(it, bookId) }
            }
        }
    }

    /**
     * Tempo und Effekte von [bookId] anwenden und ihnen folgen (Tempo-Dialog, Abo, Einstellungen). Nach der ersten
     * Anwendung nur, was sich geändert hat: Ein Tempo, das gerade anders steht (etwa vom Laden), bleibt sonst bei jeder
     * Änderung an einem Effekt nicht stehen.
     */
    private fun followEffects(bookId: Long) {
        effectsJob?.cancel()
        currentEffects = null
        effectsJob = scope.launch {
            var previous: SpokenEffects? = null
            effects.effects(bookId).collect { next ->
                if (player.currentMediaItem?.bookId != bookId) return@collect
                currentEffects = next
                if (previous?.speed?.let { sameSpeed(it, next.speed) } != true && !sameSpeed(player.playbackParameters.speed, next.speed)) {
                    player.setPlaybackSpeed(next.speed)
                }
                applyAudio(next.skipSilence, next.volumeBoostDb)
                previous = next
                updateOutro()
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val item = player.currentMediaItem
        val bookId = item?.bookId ?: return
        if (!playWhenReady) {
            rewindWhenLoaded = null
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
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: run {
            // Nach einem Kaltstart (Medientaste, Widget) kommt Play, während die Zeitachse noch lädt; ohne sie ginge die
            // Regel verloren und es ginge ohne Zurückspulen weiter.
            if (timelineBookId == bookId) rewindWhenLoaded = bookId
            return
        }
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
        seekInBook(timeline, target)
    }

    /** Springt an [positionMs] im geladenen Buch, auch über Dateigrenzen; `false`, wenn die Datei nicht im Player liegt. */
    private fun seekInBook(timeline: BookTimeline, positionMs: Long): Boolean {
        val located = timeline.locate(positionMs)
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).bookTrackIndex == located.trackIndex } ?: return false
        player.seekTo(index, located.offsetMs)
        return true
    }

    /** Zeitachse und Buchposition des geladenen Buchs bzw. der Folge; `null` bei Musik und solange die Zeitachse lädt. */
    private fun current(): Triple<MediaItem, BookTimeline, Long>? {
        val item = player.currentMediaItem ?: return null
        val bookId = item.bookId ?: return null
        val timeline = timeline?.takeIf { timelineBookId == bookId } ?: return null
        return Triple(item, timeline, timeline.toBookPosition(item.bookTrackIndex ?: 0, player.currentPosition))
    }

    /**
     * Für den Sleep-Timer „bis zum Ende“: bei Büchern mit Kapiteln jedes Kapitelende, bei Folgen und Büchern ohne Kapitel
     * nur das Ende (eine Folge läuft bis zu ihrem Ende, auch mit Kapiteln; mit Outro bis zu dessen Anfang).
     */
    fun sleepSpot(): SpokenSpot? {
        val (item, timeline, position) = current() ?: return null
        val duration = timeline.durationMs.takeIf { it > 0 } ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        val chapterEnds = if (item.isEpisode) emptyList() else timeline.chapters.map { it.endMs.coerceAtMost(duration) }.filter { it > 0 }
        val end = outroStart(item, timeline)?.takeIf { it > position } ?: duration
        val ends = (chapterEnds + end).distinct().sorted()
        return SpokenSpot(item.bookId ?: return null, position, player.playbackParameters.speed, ends)
    }

    /** Schütteln nach dem Einschlafen: [heardMs] gehörte Zeit (mit Tempo) zurück, auch über Dateigrenzen. */
    fun rewindHeard(heardMs: Long) {
        val (_, timeline, position) = current() ?: return
        seekInBook(timeline, (position - (heardMs * player.playbackParameters.speed).toLong()).coerceAtLeast(0))
    }

    /**
     * „Als gehört markieren“ für das geladene Buch bzw. die Folge: Der Stand gilt als fertig, und der Player schreibt ihn
     * nicht mehr über, bis wieder gespielt oder gespult wird. `false`, wenn [bookId] nicht geladen ist.
     */
    fun markFinished(bookId: Long): Boolean {
        if (player.currentMediaItem?.bookId != bookId) return false
        touched -= bookId
        val duration = timeline?.takeIf { timelineBookId == bookId }?.durationMs?.takeIf { it > 0 }
        persistScope.launch {
            val length = duration ?: books.bookNow(bookId)?.durationMs ?: 0L
            progressSync.record(bookId, length, length, finished = true, force = true)
        }
        return true
    }

    /** Kapitel melden, Wecker für Kapitelwechsel und Outro neu stellen (nach jedem Sprung, Tempo- oder Zustandswechsel). */
    private fun updateTimers() {
        updateChapter()
        updateOutro()
    }

    /** Wo das Outro der Folge beginnt (je Abo); `null` ohne Outro und bei Büchern. */
    private fun outroStart(item: MediaItem, timeline: BookTimeline): Long? {
        if (!item.isEpisode || currentEffects == null || timelineBookId != item.bookId) return null
        val outro = currentEffects?.skipOutroMs?.takeIf { it > 0 } ?: return null
        return (timeline.durationMs - outro).takeIf { timeline.durationMs > 0 && it > 0 }
    }

    /**
     * Outro überspringen: Erreicht die Wiedergabe den Anfang des Outros, springt sie ans Ende der Folge; die gilt damit als
     * gehört, und es geht weiter wie am Ende (nächste Folge bzw. Pause). Wer selbst ins Outro spult, hört es.
     */
    private fun updateOutro() {
        outroJob?.cancel()
        if (!player.isPlaying) return
        val (item, timeline, position) = current() ?: return
        val start = outroStart(item, timeline)?.takeIf { it > position } ?: return
        val waitMs = ((start - position) / player.playbackParameters.speed.coerceAtLeast(MIN_SPEED)).toLong()
        outroJob = scope.launch {
            delay(waitMs)
            val (nowItem, _, nowPosition) = current() ?: return@launch
            if (nowItem.bookId != item.bookId || !player.isPlaying) return@launch
            // Stille gekürzt oder Puffern: Es fehlt noch ein Stück, also noch einmal warten.
            if (nowPosition < start - OUTRO_EARLY_MS) {
                updateOutro()
                return@launch
            }
            val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            if (end != null) player.seekTo(player.currentMediaItemIndex, end) else seekInBook(timeline, timeline.durationMs)
        }
    }

    /** Meldet das laufende Kapitel und stellt den Wecker auf den nächsten Wechsel (nur während der Wiedergabe). */
    private fun updateChapter() {
        chapterJob?.cancel()
        val spot = current()?.takeIf { it.second.hasChapters }
        if (spot == null) {
            reportChapter(null)
            return
        }
        val (item, timeline, position) = spot
        val chapter = timeline.chapterAt(position)
        reportChapter(chapter?.let { (item.bookId ?: return) to it })
        if (!player.isPlaying) return
        val next = timeline.nextChapter(position) ?: return
        val waitMs = ((next.startMs - position) / player.playbackParameters.speed.coerceAtLeast(MIN_SPEED)).toLong() + CHAPTER_SLACK_MS
        chapterJob = scope.launch {
            delay(waitMs)
            updateChapter()
        }
    }

    private fun reportChapter(chapter: Pair<Long, BookChapter>?) {
        if (chapter == reportedChapter) return
        reportedChapter = chapter
        onChapterChanged(chapter?.second)
    }

    /**
     * Kopfhörer- und Medientasten ([SpokenMediaButtons]): ins nächste Kapitel bzw. an den Anfang des Kapitels oder ins
     * vorige (Regel der Kapitelknöpfe, 3 s). `false`, wenn es keine Kapitel bzw. kein weiteres gibt oder die Zeitachse
     * noch lädt.
     */
    fun skipChapter(forward: Boolean): Boolean {
        val item = player.currentMediaItem ?: return false
        val bookId = item.bookId ?: return false
        val timeline = timeline?.takeIf { timelineBookId == bookId && it.chapters.isNotEmpty() } ?: return false
        val position = timeline.toBookPosition(item.bookTrackIndex ?: 0, player.currentPosition)
        val target = if (forward) timeline.nextChapter(position)?.startMs else timeline.previousChapterStart(position)
        target ?: return false
        return seekInBook(timeline, target)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        meter.setSpeed(playbackParameters.speed)
        updateTimers()
        if (player.isPlaying) sampleRemoteEnd()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            newPosition.mediaItem?.bookId?.let { touched += it }
        }
        if (reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) countSkippedSilence(oldPosition, newPosition)
        if (reason != Player.DISCONTINUITY_REASON_REMOVE) {
            if (player.isPlaying) sampleRemoteEnd() else remoteEnd.reset()
        }
        updateTimers()
        // Ein anderes Buch (oder Musik) beginnt: den Stand des bisherigen festhalten.
        val oldItem = oldPosition.mediaItem ?: return
        val oldBookId = oldItem.bookId ?: return
        if (oldBookId == newPosition.mediaItem?.bookId) return
        record(oldBookId, oldItem.isEpisode, oldItem.bookTrackIndex ?: 0, oldPosition.positionMs, force = true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncMeter()
        // Vor dem Festhalten: Nach dem Ende auf dem Empfänger stünde sonst Position 0 der entladenen Folge im Stand.
        if (!isPlaying) checkRemoteEnd()
        updateTimers()
        val item = player.currentMediaItem
        val bookId = item?.bookId
        loop?.cancel()
        if (bookId == null) {
            remoteEnd.reset()
            return
        }
        if (isPlaying) {
            touched += bookId
            sampleRemoteEnd()
            loop = scope.launch {
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    recordCurrent(force = false)
                    sampleRemoteEnd()
                    flushListening(force = false)
                }
            }
        } else {
            recordCurrent(force = true)
            // Puffern ist keine Pause: Der Empfänger kann kurz vor dem Ende noch einmal puffern.
            if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) sampleRemoteEnd() else remoteEnd.reset()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) checkRemoteEnd()
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

    /**
     * Gekürzte Stille: Sie zählt als gehörter Inhalt (wie bei höherem Tempo), und was sie an Zeit gespart hat (geteilt
     * durch das Tempo) geht als gesparte Zeit in die Statistik.
     */
    private fun countSkippedSilence(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo) {
        val bookId = newPosition.mediaItem?.bookId ?: return
        if (bookId != meterBookId || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        val skipped = newPosition.positionMs - oldPosition.positionMs
        if (skipped <= 0) return
        meter.addContent(skipped)
        savedPending += skipped / player.playbackParameters.speed.coerceAtLeast(MIN_SPEED).toDouble()
    }

    /** Merkt sich für [remoteEnd], wo das laufende Buch gerade steht. */
    private fun sampleRemoteEnd() {
        val item = player.currentMediaItem
        val bookId = item?.bookId
        val timeline = timeline.takeIf { bookId != null && timelineBookId == bookId }
        if (item == null || bookId == null || timeline == null) {
            remoteEnd.reset()
            return
        }
        remoteEnd.playing(
            bookId = bookId,
            isEpisode = item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE,
            positionMs = timeline.toBookPosition(item.bookTrackIndex ?: 0, player.currentPosition),
            durationMs = timeline.durationMs,
            speed = player.playbackParameters.speed,
        )
    }

    /**
     * Der Cast-Empfänger steht ohne Fehler still: Lief das Buch dabei bis ans Ende, gilt es als zu Ende gehört, und eine
     * Folge geht wie lokal in die nächste über.
     */
    private fun checkRemoteEnd() {
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        if (player.playbackState != Player.STATE_IDLE || player.playerError != null) return
        val ended = remoteEnd.ended() ?: return
        // Was danach noch kommt (Position 0 der entladenen Datei), darf diesen Stand nicht überschreiben.
        touched -= ended.bookId
        persistScope.launch { progressSync.record(ended.bookId, ended.durationMs, ended.durationMs, finished = true, force = true) }
        if (ended.isEpisode) onEpisodeEnded(ended.bookId)
    }

    /** Folgen ohne (oder mit falscher) Dauer im Feed: die Datei sagt, wie lang sie wirklich ist. */
    private fun learnDuration() {
        val bookId = player.currentMediaItem?.bookId ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        scope.launch {
            if (books.learnEpisodeDuration(bookId, duration) && timelineBookId == bookId) {
                timeline = books.timeline(bookId)
                updateTimers()
            }
        }
    }

    /** Beim Beenden des Service aufrufen. */
    fun finish() {
        loop?.cancel()
        chapterJob?.cancel()
        outroJob?.cancel()
        effectsJob?.cancel()
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
        val saved = savedPending.toLong()
        savedPending -= saved
        if (meterIsEpisode) {
            persistScope.launch { stats.recordEpisodeListening(bookId, ms, savedMs = saved) }
            return
        }
        // Die Position kommt aus dem Player, solange er noch dieses Buch spielt; sonst bleibt die bisherige.
        val item = player.currentMediaItem
        val timeline = timeline.takeIf { timelineBookId == bookId && item?.bookId == bookId }
        val position = timeline?.toBookPosition(item?.bookTrackIndex ?: 0, player.currentPosition)
        val duration = timeline?.durationMs
        persistScope.launch {
            stats.recordBookListening(bookId, ms, savedMs = saved)
            sessions.record(bookId, ms, position, duration, force)
        }
    }

    /** Richtet die Messung auf das aktuelle Buch bzw. die aktuelle Folge und den Abspielzustand aus; die bisher gemessene Zeit wird zuerst verbucht. */
    private fun syncMeter() {
        flushListening()
        val item = player.currentMediaItem
        val bookId = item?.bookId
        // Was beim Verbuchen unter einer ms blieb, gehört nicht zum nächsten Buch.
        if (bookId != meterBookId) savedPending = 0.0
        meterBookId = bookId
        meterIsEpisode = item?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
        if (bookId != null && player.isPlaying) meter.start(player.playbackParameters.speed) else meter.stop()
    }

    private fun recordCurrent(force: Boolean) {
        val item = player.currentMediaItem ?: return
        val bookId = item.bookId ?: return
        record(bookId, item.isEpisode, item.bookTrackIndex ?: 0, player.currentPosition, force)
    }

    private fun record(bookId: Long, isEpisode: Boolean, trackIndex: Int, positionInTrackMs: Long, force: Boolean) {
        if (bookId !in touched) return
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: return
        val position = timeline.toBookPosition(trackIndex, positionInTrackMs)
        val current = player.currentMediaItem?.bookId == bookId
        // Angehalten: nicht mehr im Player oder nicht mehr gewollt; Puffern zählt nicht.
        val stopped = !current || !player.playWhenReady
        val lastChapterStart = timeline.chapters.lastOrNull()?.startMs ?: 0L
        val finished = isFinishedAt(position, timeline.durationMs, isEpisode, stopped, lastChapterStart) ||
            (player.playbackState == Player.STATE_ENDED && current)
        persistScope.launch { progressSync.record(bookId, position, timeline.durationMs, finished, force) }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L

        /** Erst kurz nach dem Kapitelanfang nachsehen, damit die Position sicher schon im neuen Kapitel liegt. */
        const val CHAPTER_SLACK_MS = 200L

        /** So knapp vor dem Outro gilt es schon als erreicht (der Wecker kommt nie auf die Millisekunde). */
        const val OUTRO_EARLY_MS = 300L
        const val MIN_SPEED = 0.1f
    }
}
