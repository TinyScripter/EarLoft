package de.kleinabersmart.earloft.core.playback

import android.os.SystemClock
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Everything that only applies to audiobooks (concept 12.3/12.4): record and report progress, set speed and effects by
 * precedence book or episode > subscription > area ([SpokenEffectsRepository]; they are saved where the user
 * changes them), skip outros. Runs in the service on the player thread; [scope] is the main scope, [persistScope] the app scope
 * for writing – it must outlive the service (the main scope ends in `onDestroy` right after [finish]).
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
    /** A podcast episode has played to the end and the player is waiting (parameter: book ID of the episode). */
    private val onEpisodeEnded: (Long) -> Unit,
    /** The current chapter has changed; `null` for music, without chapters and while the timeline is loading. */
    private val onChapterChanged: (BookChapter?) -> Unit = {},
    private val effects: SpokenEffectsRepository,
    /** Skip silence and volume boost on the local player; `(false, 0)` for music. */
    private val applyAudio: (skipSilence: Boolean, volumeBoostDb: Int) -> Unit = { _, _ -> },
    /** Content time skipped by "skip silence" in ms since the last call ([SkippedSilence.drainMs]). */
    private val skippedSilenceMs: () -> Long = { 0L },
    /**
     * The file (book, track index, offset) is not in the player, which only holds a window of a long book while casting: load a
     * window around it. `true` if that has started (it completes asynchronously), `false` if it is not possible.
     */
    private val seekOutsideWindow: (bookId: Long, trackIndex: Int, offsetMs: Long) -> Boolean = { _, _, _ -> false },
) : Player.Listener {

    private var timelineBookId: Long? = null
    private var timeline: BookTimeline? = null
    private var loop: Job? = null

    /** Last reported chapter (with book) and the alarm for the next chapter change. */
    private var reportedChapter: Pair<Long, BookChapter>? = null
    private var chapterJob: Job? = null

    /** What applies to the loaded book or episode ([timelineBookId]), its observer and the alarm for the outro. */
    private var currentEffects: SpokenEffects? = null
    private var effectsJob: Job? = null
    private var outroJob: Job? = null

    /** Observer of the saved state of the loaded book ([followStoredProgress]). */
    private var storedProgressJob: Job? = null

    // Listened time of the current book or episode (statistics); the measured time belongs to [meterBookId].
    private val meter = ListeningMeter()
    private var meterBookId: Long? = null
    private var meterIsEpisode = false

    /** Time saved by "skip silence" (wall clock) on [meterBookId] since the last booking. */
    private var savedPending = 0.0

    // Resume settings; kept at hand so that rewinding on pressing play takes effect without delay.
    private var bookRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S)
    private var podcastRule = bookRule
    private var restartChapter = false

    /** When and in which book the user last paused playback. */
    private var pausedBookId: Long? = null
    private var pausedAt = 0L

    /** Play came before the book's timeline was loaded (cold start via headphones): apply the rule afterwards. */
    private var rewindWhenLoaded: Long? = null

    /** Restored paused ([restoredPaused]), but [onMediaItemTransition] has not come yet: book and start of pause. */
    private var pendingRestore: Pair<Long, Long>? = null

    /**
     * Books that were played or seeked since loading. Only their state is recorded: otherwise a book that was merely loaded
     * would write its old position with a new timestamp on switching or stopping, overwriting a
     * newer state from another device.
     */
    private val touched = mutableSetOf<Long>()

    /** The player jumps to a state that another device saved ([followStoredProgress]): that is not a seek of this device. */
    private var adopting = false

    /**
     * Such a jump the player held back instead of reporting it right away (casting, while the receiver's queue is refilled): when it
     * is carried out later, it is still the other device's state, not a seek of this one (review4 8.6 Y7). Until [HeldAdoption.until].
     */
    private var heldAdoption: HeldAdoption? = null

    /** Seeks reported by the player, to tell whether an adopting jump was reported right away. */
    private var seekReports = 0

    /**
     * The last states this device saved, at most [OWN_SAVES]. Each save is written on its own, so after quick seeks while paused
     * an earlier one can land last; [followStoredProgress] must not take it for a foreign state and pull the player back
     * (review4 8.6 Y7). Only a row written shortly after the save counts as it ([OWN_SAVE_LANDS_MS]): a later state of another
     * device at the same spot (both jumped to a chapter start, or reset to 0) is foreign and is adopted.
     */
    private val ownSaves = ArrayDeque<OwnSave>()

    /**
     * A jump outside the cast window loads a new window ([seekOutsideWindow]) for this book until this time (elapsed realtime).
     * The receiver empties its queue before the window arrives; that is no end of the book and no switch to another one.
     */
    private var windowLoad: Pair<Long, Long>? = null

    /** Cast reports no `STATE_ENDED`; the watchdog detects the end when the receiver stands still. */
    private val remoteEnd = RemoteEndWatch()

    /** The queue just went to the cast receiver ([awaitsRemoteQueue]); it loads it at normal speed. */
    private var handingOver = false

    /** The item before the last transition: if the player came from a later one (skipped backwards), [skipUnavailable] skips backwards. */
    private var lastItemIndex = C.INDEX_UNSET

    init {
        scope.launch { settings.bookResume.collect { bookRule = it } }
        scope.launch { settings.podcastResume.collect { podcastRule = it } }
        scope.launch { settings.restartChapter.collect { restartChapter = it } }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncMeter()
        // Something else than the book of the window was started: an empty queue no longer belongs to the window load. Items of
        // the book itself may come before the receiver's empty queue (the player shows the new queue at once), so they keep it.
        if (mediaItem != null && mediaItem.bookId != windowLoad?.first) windowLoad = null
        val previousIndex = lastItemIndex
        lastItemIndex = player.currentMediaItemIndex
        if (mediaItem?.isUnavailablePart == true && !player.awaitsRemoteQueue && skipUnavailable(previousIndex)) return
        val bookId = mediaItem?.bookId
        val handedOver = handingOver
        handingOver = false
        if (bookId == null) {
            if (player.awaitsRemoteQueue) {
                handingOver = true
                return
            }
            // Music always plays at normal speed and without effects.
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            effectsJob?.cancel()
            storedProgressJob?.cancel()
            currentEffects = null
            applyAudio(false, 0)
            updateTimers()
            return
        }
        // Within a book (next file) everything stays; for a different book, its speed applies.
        if (timelineBookId == bookId) {
            // Back from music to the same book: the music turned off the effects and ended their observer.
            if (effectsJob?.isActive != true) followEffects(bookId)
            if (storedProgressJob?.isActive != true) followStoredProgress(bookId)
            // The cast receiver loads the queue at normal speed.
            if (handedOver) currentEffects?.speed?.takeUnless { sameSpeed(it, player.playbackParameters.speed) }?.let(player::setPlaybackSpeed)
            updateTimers()
            return
        }
        timelineBookId = bookId
        followEffects(bookId)
        followStoredProgress(bookId)
        // A freshly loaded book already got its start position from the saved state – unless it was
        // restored paused; then the pause since the saved state counts.
        val restored = pendingRestore?.takeIf { it.first == bookId }
        pendingRestore = null
        pausedBookId = restored?.first
        pausedAt = restored?.second ?: 0L
        timeline = null
        updateTimers()
        scope.launch {
            val loaded = books.timeline(bookId)
            // Switched to another book while loading: chapters, position and sleep timer of this one must not apply to it (review2 J35).
            if (timelineBookId != bookId) return@launch
            timeline = loaded
            updateTimers()
            if (rewindWhenLoaded == bookId) {
                rewindWhenLoaded = null
                player.currentMediaItem?.takeIf { it.bookId == bookId }?.let { rewindAfterPause(it, bookId) }
            }
        }
    }

    /**
     * Skips over a file of a book that is no longer available ([isUnavailablePart]): in the direction the player
     * came from (backwards if from a later item, otherwise forwards), otherwise in the other one. `false` if there is no playable item;
     * the player then stops.
     */
    private fun skipUnavailable(previousIndex: Int): Boolean {
        val index = player.currentMediaItemIndex
        val forward = (index + 1 until player.mediaItemCount).firstOrNull { !player.getMediaItemAt(it).isUnavailablePart }
        val backward = (index - 1 downTo 0).firstOrNull { !player.getMediaItemAt(it).isUnavailablePart }
        val target = if (previousIndex != C.INDEX_UNSET && previousIndex > index) backward ?: forward else forward ?: backward
        if (target == null) {
            player.pause()
            return false
        }
        player.seekTo(target, 0)
        return true
    }

    /**
     * Apply the speed and effects of [bookId] and follow them (speed dialog, subscription, settings). After the first
     * application only what has changed: otherwise a speed that is currently different (e.g. from loading) would not
     * stay put on every change to an effect.
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

    /**
     * Another device has changed the state of the loaded, paused book (watch ↔ phone, sync with Audiobookshelf): the
     * player jumps there. Otherwise play would continue at the old position, and the next save would write it with a
     * new timestamp over the newer state. Only changes after loading count; loading happens at the saved
     * state anyway. Whoever saved the foreign state has already decided whether it is newer.
     */
    private fun followStoredProgress(bookId: Long) {
        storedProgressJob?.cancel()
        storedProgressJob = scope.launch {
            // Only a new state counts, not another field of the row: the tempo, the effects or "sent" change it without moving
            // the position, and a paused player that was scrubbed since its last save must not jump back then (review4 T3).
            books.book(bookId).map { it?.progress }.distinctUntilChangedBy { it?.updatedAt }.drop(1).collect { stored ->
                stored ?: return@collect
                val (item, timeline, position) = current() ?: return@collect
                if (item.bookId != bookId) return@collect
                if (ownSaves.any { it.matches(bookId, stored) }) return@collect
                val target = storedPositionToAdopt(stored, position, player.playWhenReady) ?: return@collect
                val reportsBefore = seekReports
                adopting = true
                val jumped = try {
                    seekInBook(timeline, target)
                } finally {
                    adopting = false
                }
                if (!jumped) return@collect
                heldAdoption = if (seekReports == reportsBefore) {
                    val located = timeline.locate(target)
                    HeldAdoption(bookId, located.trackIndex, located.offsetMs, SystemClock.elapsedRealtime() + WINDOW_LOAD_MS)
                } else {
                    null
                }
                // The jump is not a state of this device (ExoPlayer reports it as a seek synchronously): otherwise switching away later
                // or the service ending would write this position with a new timestamp over what the other device stores after it.
                touched -= bookId
                // The resume rule counts from the pause on the other device.
                pausedBookId = bookId
                pausedAt = stored.updatedAt
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
     * The book [bookId] was restored paused (service start); [pausedSinceMs] is the time of the
     * saved state. On the first play the resume rule then applies as after a pause in the player.
     */
    fun restoredPaused(bookId: Long, pausedSinceMs: Long) {
        if (timelineBookId == bookId) {
            pausedBookId = bookId
            pausedAt = pausedSinceMs
        } else {
            pendingRestore = bookId to pausedSinceMs
        }
    }

    /** Playback after a pause in the same book: after a long pause rewind a bit, possibly to the chapter start. */
    private fun rewindAfterPause(item: MediaItem, bookId: Long) {
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: run {
            // After a cold start (media button, widget) play arrives while the timeline is still loading; without it the
            // rule would be lost and playback would continue without rewinding.
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

    /** Seeks to [positionMs] in the loaded book, also across file boundaries; `false` if that is not possible. */
    private fun seekInBook(timeline: BookTimeline, positionMs: Long): Boolean {
        val located = timeline.locate(positionMs)
        return seekToTrack(located.trackIndex, located.offsetMs)
    }

    /**
     * Seeks to [offsetMs] in the file [trackIndex] of the loaded book. While casting, the player holds only a window of a book with
     * many files; a file outside of it comes with a new window ([seekOutsideWindow]), otherwise chapter jumps, the chapter list,
     * rewinding after a pause or shaking would go nowhere (review4 T11). `false` without a loaded book or if that is not possible.
     */
    fun seekToTrack(trackIndex: Int, offsetMs: Long): Boolean {
        val item = player.currentMediaItem ?: return false
        val bookId = item.bookId ?: return false
        val index = (0 until player.mediaItemCount).firstOrNull { index ->
            player.getMediaItemAt(index).let { it.bookId == bookId && it.bookTrackIndex == trackIndex }
        }
        if (index == null) {
            if (!seekOutsideWindow(bookId, trackIndex, offsetMs)) return false
            // The receiver empties its queue before the new window arrives: that looks like the end of the book, and the old
            // position would be saved over the jump with force (seen on the device), or the book would count as finished if the
            // jump started close to its end.
            windowLoad = bookId to SystemClock.elapsedRealtime() + WINDOW_LOAD_MS
            remoteEnd.reset()
            // The new window comes with new items, not as a seek: the jump is saved here as in onPositionDiscontinuity
            // (review4 8.6 Y8), while playing too. Adopting a foreign state is no state of this device.
            if (!adopting) {
                touched += bookId
                val paused = !player.playWhenReady
                record(bookId, item.isEpisode, trackIndex, offsetMs, force = false, keepHidden = paused, seek = true)
                // Paused, the state is saved; playing touches it again.
                if (paused) touched -= bookId
            }
            return true
        }
        player.seekTo(index, offsetMs)
        return true
    }

    /**
     * Seeks [deltaMs] from the current position in the loaded book, also to a file outside the cast window ([seekToTrack]); "-10 s" at
     * the start of the window's first file went to its start instead of into the previous file (review5 P13). `false` without a
     * loaded book or timeline.
     */
    fun seekBy(deltaMs: Long): Boolean {
        val (_, timeline, position) = current() ?: return false
        if (timeline.durationMs <= 0) return false
        return seekInBook(timeline, (position + deltaMs).coerceIn(0, timeline.durationMs))
    }

    /** Timeline and book position of the loaded book or episode; `null` for music and while the timeline is loading. */
    private fun current(): Triple<MediaItem, BookTimeline, Long>? {
        val item = player.currentMediaItem ?: return null
        val bookId = item.bookId ?: return null
        val timeline = timeline?.takeIf { timelineBookId == bookId } ?: return null
        return Triple(item, timeline, timeline.toBookPosition(item.bookTrackIndex ?: 0, player.currentPosition))
    }

    /**
     * For the sleep timer "until the end": for books with chapters every chapter end, for episodes and books without chapters
     * only the end (an episode plays to its end, even with chapters; with an outro up to its start).
     */
    fun sleepSpot(): SpokenSpot? {
        val (item, timeline, position) = current() ?: return null
        val duration = timeline.durationMs.takeIf { it > 0 } ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        val chapterEnds = if (item.isEpisode) emptyList() else timeline.chapters.map { it.endMs.coerceAtMost(duration) }.filter { it > 0 }
        val end = outroStart(item, timeline)?.takeIf { it > position } ?: duration
        val ends = (chapterEnds + end).distinct().sorted()
        return SpokenSpot(item.bookId ?: return null, position, player.playbackParameters.speed, ends)
    }

    /** Shaking after falling asleep: rewind [heardMs] of listened time (with speed), also across file boundaries. */
    fun rewindHeard(heardMs: Long) {
        val (_, timeline, position) = current() ?: return
        seekInBook(timeline, (position - (heardMs * player.playbackParameters.speed).toLong()).coerceAtLeast(0))
    }

    /**
     * "Mark as played" for the loaded book or episode: the state counts as finished, and the player no longer
     * overwrites it until it is played or seeked again. `false` if [bookId] is not loaded.
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

    /** Report the chapter, reset the alarms for chapter change and outro (after every seek, speed or state change). */
    private fun updateTimers() {
        updateChapter()
        updateOutro()
    }

    /** Where the episode's outro begins (per subscription); `null` without an outro and for books. */
    private fun outroStart(item: MediaItem, timeline: BookTimeline): Long? {
        if (!item.isEpisode || currentEffects == null || timelineBookId != item.bookId) return null
        val outro = currentEffects?.skipOutroMs?.takeIf { it > 0 } ?: return null
        return (timeline.durationMs - outro).takeIf { timeline.durationMs > 0 && it > 0 }
    }

    /**
     * Skip outro: when playback reaches the start of the outro, it jumps to the end of the episode; it then counts as
     * played, and things continue as at the end (next episode or pause). Whoever seeks into the outro themselves hears it.
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
            // Silence skipped or buffering: a bit is still missing, so wait once more.
            if (nowPosition < start - OUTRO_EARLY_MS) {
                updateOutro()
                return@launch
            }
            val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            if (end != null) player.seekTo(player.currentMediaItemIndex, end) else seekInBook(timeline, timeline.durationMs)
        }
    }

    /** Reports the current chapter and sets the alarm for the next change (only during playback). */
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
     * Headphone and media buttons ([SpokenMediaButtons]): to the next chapter, or to the start of the chapter or the
     * previous one (chapter button rule, 3 s). `false` if there are no chapters or no further one, or the timeline
     * is still loading.
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

    /**
     * Back from the cast receiver to the phone: the receiver limits the speed to 2.0 without a word, and the handover carries that
     * limited speed to the phone. The book's own speed applies again (review4 T12). Afterwards, because the handover sets the
     * transferred speed only after the device has changed.
     */
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        scope.launch {
            val bookId = player.currentMediaItem?.bookId ?: return@launch
            if (bookId != timelineBookId || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return@launch
            currentEffects?.speed?.takeUnless { sameSpeed(it, player.playbackParameters.speed) }?.let(player::setPlaybackSpeed)
        }
    }

    private fun onSeek(newPosition: Player.PositionInfo) {
        seekReports++
        val seeked = newPosition.mediaItem
        val seekedBookId = seeked?.bookId
        // The first seek after a held-back adopting jump decides: either it is that jump, or the user was quicker.
        val held = heldAdoption.takeIf { !adopting }?.also { heldAdoption = null }
        if (held != null && seekedBookId != null && held.matches(seekedBookId, seeked.bookTrackIndex ?: 0, newPosition.positionMs)) {
            // Played meanwhile (cast, the jump came only after "play"): the book is touched here again, its position from now on
            // is this device's state; untouched, neither the 15 s beat nor the next pause would save it (review5 P16).
            if (!player.playWhenReady) touched -= seekedBookId
            return
        }
        seekedBookId?.let { touched += it }
        // Scrubbing a paused book is its new state: it is only saved when the playback stops, and nothing would stop here. Without
        // it, the next foreign write to the row would see a position other than the player's and move it back (review4 T3).
        // A book taken out of "Continue listening" stays out: the jump to the start after "unplayed" or a reset comes this way
        // too (review4 8.6 Y2). A seek is no stop: scrubbing close to the end does not finish the book yet (review4 8.6 Y6).
        if (!adopting && !player.playWhenReady && seeked != null && seekedBookId != null) {
            record(
                seekedBookId, seeked.isEpisode, seeked.bookTrackIndex ?: 0, newPosition.positionMs, force = false,
                keepHidden = true, seek = true,
            )
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) onSeek(newPosition)
        if (reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) collectSkippedSilence()
        if (reason != Player.DISCONTINUITY_REASON_REMOVE) {
            if (player.isPlaying) sampleRemoteEnd() else remoteEnd.reset()
        }
        updateTimers()
        // Another book (or music) starts: record the state of the previous one.
        val oldItem = oldPosition.mediaItem ?: return
        val oldBookId = oldItem.bookId ?: return
        if (oldBookId == newPosition.mediaItem?.bookId) return
        if (newPosition.mediaItem == null && loadsWindow(oldBookId)) return
        record(oldBookId, oldItem.isEpisode, oldItem.bookTrackIndex ?: 0, oldPosition.positionMs, force = true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncMeter()
        // Before recording: otherwise, after the end on the receiver, position 0 of the unloaded episode would be in the state.
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
            // Buffering is not a pause: the receiver may buffer once more shortly before the end.
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
     * Skipped silence counts as listened content (as with a higher speed), and the time it saved (divided
     * by the speed) goes into the statistics as saved time.
     */
    private fun collectSkippedSilence() {
        val skipped = skippedSilenceMs()
        // It is off for music; anything that still comes belongs to no book.
        if (skipped <= 0 || meterBookId == null) return
        meter.addContent(skipped)
        savedPending += skipped / player.playbackParameters.speed.coerceAtLeast(MIN_SPEED).toDouble()
    }

    /** Remembers for [remoteEnd] where the current book currently is. */
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
     * The cast receiver stands still without an error: if the book ran to the end, it counts as finished, and an
     * episode moves on to the next one as it does locally.
     */
    private fun checkRemoteEnd() {
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        if (player.playbackState != Player.STATE_IDLE || player.playerError != null) return
        // The empty queue before a new window is no end.
        if (player.currentMediaItem == null && windowLoad?.let { loadsWindow(it.first) } == true) return
        val ended = remoteEnd.ended() ?: return
        // Whatever comes afterwards (position 0 of the unloaded file) must not overwrite this state.
        touched -= ended.bookId
        persistScope.launch { progressSync.record(ended.bookId, ended.durationMs, ended.durationMs, finished = true, force = true) }
        if (ended.isEpisode) onEpisodeEnded(ended.bookId)
    }

    /** Episodes without (or with a wrong) duration in the feed: the file tells how long it really is. */
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

    /** Call when the service ends. */
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
     * Write the time listened on [meterBookId] since the last time into the statistics; for books also report it as a
     * session to the server ([force]: immediately instead of throttled).
     */
    private fun flushListening(force: Boolean = true) {
        collectSkippedSilence()
        val bookId = meterBookId ?: return
        val ms = meter.drain()
        if (ms <= 0) return
        val saved = savedPending.toLong()
        savedPending -= saved
        if (meterIsEpisode) {
            persistScope.launch { stats.recordEpisodeListening(bookId, ms, savedMs = saved) }
            return
        }
        // The position comes from the player as long as it still plays this book; otherwise the previous one stays.
        val item = player.currentMediaItem
        val timeline = timeline.takeIf { timelineBookId == bookId && item?.bookId == bookId }
        val position = timeline?.toBookPosition(item?.bookTrackIndex ?: 0, player.currentPosition)
        val duration = timeline?.durationMs
        persistScope.launch {
            stats.recordBookListening(bookId, ms, savedMs = saved)
            sessions.record(bookId, ms, position, duration, force)
        }
    }

    /** Aligns the meter with the current book or episode and the playback state; the time measured so far is booked first. */
    private fun syncMeter() {
        flushListening()
        val item = player.currentMediaItem
        val bookId = item?.bookId
        // Whatever stayed below one ms when booking does not belong to the next book.
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

    private fun record(
        bookId: Long,
        isEpisode: Boolean,
        trackIndex: Int,
        positionInTrackMs: Long,
        force: Boolean,
        keepHidden: Boolean = false,
        seek: Boolean = false,
    ) {
        if (bookId !in touched) return
        val timeline = timeline.takeIf { timelineBookId == bookId } ?: return
        val position = timeline.toBookPosition(trackIndex, positionInTrackMs)
        val current = player.currentMediaItem?.bookId == bookId
        // Stopped: no longer in the player or no longer wanted; buffering doesn't count. A seek while paused is not a stop.
        val stopped = !seek && (!current || !player.playWhenReady)
        val lastChapterStart = timeline.chapters.lastOrNull()?.startMs ?: 0L
        val finished = isFinishedAt(position, timeline.durationMs, isEpisode, stopped, lastChapterStart) ||
            (player.playbackState == Player.STATE_ENDED && current)
        ownSaves.addLast(OwnSave(bookId, position, System.currentTimeMillis()))
        if (ownSaves.size > OWN_SAVES) ownSaves.removeFirst()
        persistScope.launch { progressSync.record(bookId, position, timeline.durationMs, finished, force, keepHidden = keepHidden) }
    }

    /** A window of [bookId] is on its way to the receiver ([windowLoad]). */
    private fun loadsWindow(bookId: Long): Boolean {
        val (loading, until) = windowLoad ?: return false
        if (SystemClock.elapsedRealtime() > until) windowLoad = null
        return loading == bookId && windowLoad != null
    }

    /** A state this device saved: book, position in the book, wall-clock time of the save (as `updatedAt` of the row). */
    /** See [heldAdoption]; [until] in elapsed realtime. */
    private class HeldAdoption(val bookId: Long, val trackIndex: Int, val offsetMs: Long, val until: Long) {
        fun matches(bookId: Long, trackIndex: Int, positionMs: Long): Boolean =
            bookId == this.bookId && trackIndex == this.trackIndex && abs(positionMs - offsetMs) <= ADOPT_TOLERANCE_MS &&
                SystemClock.elapsedRealtime() <= until
    }

    private class OwnSave(val bookId: Long, val positionMs: Long, val at: Long) {
        /** Is [stored] the row of this save: same book, same spot, written right after it? */
        fun matches(bookId: Long, stored: BookProgress): Boolean =
            bookId == this.bookId && abs(positionMs - stored.positionMs) <= ADOPT_TOLERANCE_MS &&
                abs(stored.updatedAt - at) <= OWN_SAVE_LANDS_MS
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L

        /** Own saves that can still be on their way to the database: a few quick seeks while paused. */
        const val OWN_SAVES = 8

        /** An own save is in the database this soon after it was made; a row stamped later at the same spot is foreign. */
        const val OWN_SAVE_LANDS_MS = 10_000L

        /** The receiver has the new window by then; afterwards an empty queue counts as such again. */
        const val WINDOW_LOAD_MS = 15_000L

        /** Only check shortly after the chapter start, so the position is surely in the new chapter already. */
        const val CHAPTER_SLACK_MS = 200L

        /** This close to the outro it already counts as reached (the alarm never hits the exact millisecond). */
        const val OUTRO_EARLY_MS = 300L
        const val MIN_SPEED = 0.1f
    }
}


/**
 * Where the paused player jumps when the saved state [stored] of its book changes; `null` if it stays:
 * during playback ([playWhenReady]), for a state marked as finished, and for own saves that are at the
 * player's position [playerPositionMs] (both across the whole book).
 */
internal fun storedPositionToAdopt(stored: BookProgress, playerPositionMs: Long, playWhenReady: Boolean): Long? {
    if (playWhenReady || stored.finished) return null
    if (abs(stored.positionMs - playerPositionMs) <= ADOPT_TOLERANCE_MS) return null
    return stored.positionMs
}

/** Own saves differ at most by the rounding; foreign states by more. */
private const val ADOPT_TOLERANCE_MS = 1_000L
