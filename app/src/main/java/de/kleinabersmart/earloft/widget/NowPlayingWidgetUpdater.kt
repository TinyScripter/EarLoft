package de.kleinabersmart.earloft.widget

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.seedColorOf
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.CoverProvider
import de.kleinabersmart.earloft.core.playback.NowPlaying
import de.kleinabersmart.earloft.core.playback.NowPlayingObserver
import de.kleinabersmart.earloft.core.playback.ServicePlayer
import de.kleinabersmart.earloft.ui.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Playing time of a book or episode: position and length in book time (length 0 = unknown) and the speed, measured at
 * [measuredAtMs] ([SystemClock.elapsedRealtime]).
 */
data class BookClock(
    val bookId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val running: Boolean,
    val measuredAtMs: Long,
) {
    /** Does the widget count by itself (chronometer in the launcher)? Only at 1×, because the chronometer runs in real time. */
    val ticksItself: Boolean get() = running && abs(speed - 1f) < SPEED_TOLERANCE

    /** State at time [nowMs]: if it is running, book time has elapsed since the measurement (real time times speed). */
    fun at(nowMs: Long): BookClock {
        if (!running) return this
        val position = positionMs + ((nowMs - measuredAtMs) * speed).toLong()
        return copy(positionMs = if (durationMs > 0) position.coerceAtMost(durationMs) else position, measuredAtMs = nowMs)
    }

    /** Stopped at time [nowMs]. */
    fun stoppedAt(nowMs: Long): BookClock = if (running) at(nowMs).copy(running = false) else this

    /** Real time until the hours gain a digit and the chronometer needs a different prefix; `null` = never. */
    fun msUntilPrefixChange(): Long? {
        val next = PREFIX_CHANGES_MS.firstOrNull { it > positionMs } ?: return null
        return untilBookTime(next - positionMs)
    }

    private fun untilBookTime(bookMs: Long): Long = (bookMs / speed.coerceAtLeast(MIN_SPEED)).toLong() + TICK_SLACK_MS
}

/** What the widgets show for an area: what is playing or what was last played from it. */
data class WidgetEntry(
    val nowPlaying: NowPlaying,
    /**
     * Cover via the [CoverProvider]; `null` = none. The launcher loads it itself instead of receiving it as an
     * image with every update (at speed ≠ 1× every 10 s).
     */
    val coverUri: Uri? = null,
    /** Width divided by height of the cover (album square, book mostly portrait). */
    val coverAspectRatio: Float = 1f,
    /** Base color of the cover (ARGB) for the widget colors; `null` = app colors. */
    val seedColor: Int? = null,
    /** Playing time for books and episodes (stopped); for the area in the player, [WidgetState.clock] applies. */
    val clock: BookClock? = null,
    /** When the area was last in the player (wall clock). */
    val lastActiveAt: Long = 0,
)

data class WidgetState(
    /** Per area what is playing or was last played. */
    val entries: Map<SpokenMode, WidgetEntry> = emptyMap(),
    /** The area of what is or was last in the player. */
    val currentArea: SpokenMode? = null,
    /** Playing time of the book or episode in the player; `null` for music or as long as it isn't known. */
    val clock: BookClock? = null,
    /** Skip intervals from the settings, for books and episodes. */
    val bookSeek: SeekSteps = SeekSteps.DEFAULT,
    val podcastSeek: SeekSteps = SeekSteps.DEFAULT,
) {
    /**
     * What a widget shows for [areas]: what is in the player if it belongs to them, otherwise what was last played from them
     * (paused); `null` if nothing from these areas has ever played.
     */
    fun entryFor(areas: Set<SpokenMode>): WidgetEntry? {
        val current = currentArea?.takeIf { it in areas }?.let(entries::get)
        if (current != null) return current.copy(clock = clock?.takeIf { it.bookId == current.nowPlaying.bookId } ?: current.clock)
        return entries.filterKeys { it in areas }.values.maxByOrNull { it.lastActiveAt }
    }

    /**
     * New playing time in the player. If the previous one belonged to another book or episode (the tick reports the change
     * before the new entry), it stays there, stopped at time [nowMs].
     */
    fun withClock(clock: BookClock?, nowMs: Long): WidgetState {
        val previous = this.clock?.takeIf { it.bookId != clock?.bookId } ?: return copy(clock = clock)
        val stopped = previous.stoppedAt(nowMs)
        return copy(
            clock = clock,
            entries = entries.mapValues { (_, entry) -> if (entry.nowPlaying.bookId == previous.bookId) entry.copy(clock = stopped) else entry },
        )
    }

    /** Is [entry] in the player? Otherwise "Play" resumes it first. */
    fun isCurrent(entry: WidgetEntry): Boolean = entry.nowPlaying.area == currentArea

    /** Skip intervals for a book or episode. */
    fun seekOf(nowPlaying: NowPlaying): SeekSteps = if (nowPlaying.isEpisode) podcastSeek else bookSeek
}

/**
 * Holds the state for the widget and updates it with throttling. The last state stays even when the service
 * ends, and survives the process ([WidgetStateStore]). The PlaybackService notifies it even when the
 * app UI isn't running.
 */
@Singleton
class NowPlayingWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artworkRepository: ArtworkRepository,
    private val albumColorRepository: AlbumColorRepository,
    private val bookCovers: BookCovers,
    private val books: BookRepository,
    private val servicePlayer: ServicePlayer,
    private val store: WidgetStateStore,
    spokenSettings: SpokenSettings,
    themeSettings: ThemeSettings,
    @ApplicationScope scope: CoroutineScope,
) : NowPlayingObserver {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val coverVersion = System.currentTimeMillis().toString()
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()

    /** Is at least one widget placed? Without a widget no playing time is measured. */
    private val widgetsPlaced = MutableStateFlow(false)

    init {
        scope.launch {
            if (GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) widgetsPlaced.value = true
        }
        scope.launch { configureLegacyWidgets(themeSettings) }
        scope.launch {
            restore()
            // Save only after loading, otherwise the empty initial state would overwrite the remembered one. Only with a stopped
            // clock, so the tick doesn't write every 10 s at speed ≠ 1×; pause and end of the service stop it.
            _state
                .filter { it.clock?.running != true }
                .map { state -> state.entries.values.map { state.saved(it) } }
                .filter { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { store.save(it) }
        }
        combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, ::Pair)
            .onEach { (book, podcast) ->
                _state.value = _state.value.copy(bookSeek = book, podcastSeek = podcast)
                NowPlayingWidget().updateAll(context)
            }
            .launchIn(scope)
        @OptIn(FlowPreview::class)
        nowPlaying
            .debounce(UPDATE_THROTTLE_MS)
            .onEach { update(it) }
            .launchIn(scope)
        @OptIn(ExperimentalCoroutinesApi::class)
        combine(nowPlaying, widgetsPlaced, ::Pair)
            .flatMapLatest { (nowPlaying, placed) -> if (placed) clockUpdates(nowPlaying) else emptyFlow() }
            .onEach { clock ->
                _state.update { it.withClock(clock, SystemClock.elapsedRealtime()) }
                NowPlayingWidget().updateAll(context)
            }
            .launchIn(scope)
    }

    /** A widget is displayed (Glance builds it). */
    fun onWidgetShown() {
        widgetsPlaced.value = true
    }

    /** The last widget was removed. */
    fun onAllWidgetsRemoved() {
        widgetsPlaced.value = false
    }

    /**
     * Widgets from before the per-widget settings (or from a launcher that skips the dialog when adding):
     * all areas, colors like the former "Cover colors in widget" switch.
     */
    private suspend fun configureLegacyWidgets(themeSettings: ThemeSettings) {
        val manager = GlanceAppWidgetManager(context)
        val unconfigured = manager.getGlanceIds(NowPlayingWidget::class.java).filter { id ->
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id).widgetConfig() == null
        }
        if (unconfigured.isEmpty()) return
        val colors = when {
            themeSettings.widgetCoverColors.first() -> WidgetColors.COVER
            themeSettings.systemColors.first() -> WidgetColors.SYSTEM
            else -> WidgetColors.APP
        }
        unconfigured.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                if (prefs.widgetConfig() == null) prefs.setWidgetConfig(WidgetConfig(colors = colors))
            }
            NowPlayingWidget().update(context, id)
        }
    }

    /**
     * After process start, show the remembered state per area; whatever the service has reported in the meantime takes precedence. The
     * player then holds the most recently played area.
     */
    private suspend fun restore() {
        val saved = store.load()
        val latest = saved.values.maxByOrNull { it.lastActiveAt } ?: return
        val now = SystemClock.elapsedRealtime()
        val entries = saved.mapValues { (_, it) ->
            val bookId = it.nowPlaying.bookId
            val clock = if (bookId != null && it.positionMs != null) {
                BookClock(bookId, it.positionMs, it.durationMs ?: 0, speed = 1f, running = false, now)
            } else {
                null
            }
            entry(it.nowPlaying, previous = null).copy(clock = clock, lastActiveAt = it.lastActiveAt)
        }
        entries.values.forEach { entry -> entry.coverUri?.let(::grantCoverAccess) }
        _state.update { state ->
            state.copy(entries = entries + state.entries, currentArea = state.currentArea ?: latest.nowPlaying.area)
        }
        if (!nowPlaying.compareAndSet(null, latest.nowPlaying)) NowPlayingWidget().updateAll(context)
    }

    /** What is stored for the area of [entry]: paused, with the playing time where it currently is. */
    private fun WidgetState.saved(entry: WidgetEntry): SavedWidget {
        val clock = if (isCurrent(entry)) clock?.takeIf { it.bookId == entry.nowPlaying.bookId } ?: entry.clock else entry.clock
        return SavedWidget(entry.nowPlaying.copy(isPlaying = false), clock?.positionMs, clock?.durationMs, entry.lastActiveAt)
    }

    /**
     * The playing time of a book or episode: measured at start, on pause, on seeking and on speed changes
     * ([measured]); in between it moves on every [TICK_INTERVAL_MS] of real time.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun clockUpdates(nowPlaying: NowPlaying?): Flow<BookClock?> {
        val bookId = nowPlaying?.bookId ?: return flowOf(null)
        return flow {
            val timeline = books.timeline(bookId)
            val measurements = merge(flowOf(Unit), servicePlayer.timingChanges().debounce(UPDATE_THROTTLE_MS))
            emitAll(measurements.flatMapLatest { measured(bookId, timeline, nowPlaying.isPlaying) })
        }.distinctUntilChanged()
    }

    /**
     * A measurement and, while playing, the tick afterwards. At 1× the chronometer in the launcher counts by itself, and only
     * while the widget is visible; it is re-measured only when the hours gain a digit. At other speeds
     * it can't keep count (it runs in real time): then the app resets the time every 10 s, as long as the widget may
     * be visible ([widgetMaybeVisible]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun measured(bookId: Long, timeline: BookTimeline?, playing: Boolean): Flow<BookClock?> = flow {
        var clock = currentClock(bookId, timeline, playing)
        emit(clock)
        if (clock == null || !clock.running) return@flow
        if (clock.ticksItself) {
            while (clock != null && clock.running) {
                delay(clock.msUntilPrefixChange() ?: return@flow)
                clock = currentClock(bookId, timeline, running = true)
                emit(clock)
            }
        } else {
            emitAll(widgetMaybeVisible().flatMapLatest { visible -> if (visible) periodicTicks(bookId, timeline) else emptyFlow() })
        }
    }

    /**
     * The playing time every [TICK_INTERVAL_MS] of real time, so at 1.5× it jumps by 15 seconds. Extrapolated from the
     * last measurement, because the player reports its position in jumps of a few hundred milliseconds. It is re-measured
     * every minute.
     */
    private fun periodicTicks(bookId: Long, timeline: BookTimeline?): Flow<BookClock?> = flow {
        var measured = currentClock(bookId, timeline, running = true)
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (measured != null && measured.running && now - measured.measuredAtMs >= REMEASURE_MS) {
                measured = currentClock(bookId, timeline, running = true)
            }
            val clock = measured?.at(now)
            emit(clock)
            if (clock == null || !clock.running) break
            delay(TICK_INTERVAL_MS - (SystemClock.elapsedRealtime() - start) % TICK_INTERVAL_MS)
        }
    }

    /**
     * Can the widget be visible right now? Screen on and unlocked, and the app itself isn't in the foreground. Whether
     * the home screen is in front, an app can't find out. The receivers are only registered while the tick is running.
     */
    private fun widgetMaybeVisible(): Flow<Boolean> =
        combine(screenUnlocked(), ProcessLifecycleOwner.get().lifecycle.currentStateFlow) { unlocked, appState ->
            unlocked && !appState.isAtLeast(Lifecycle.State.STARTED)
        }.distinctUntilChanged()

    private fun screenUnlocked(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(isScreenUnlocked())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(isScreenUnlocked())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun isScreenUnlocked(): Boolean = powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    /** Without a loaded book (service ended, new process), the last playing time stays. */
    private suspend fun currentClock(bookId: Long, timeline: BookTimeline?, running: Boolean): BookClock? = withContext(Dispatchers.Main.immediate) {
        val now = SystemClock.elapsedRealtime()
        val position = servicePlayer.bookPosition(bookId)
            ?: return@withContext _state.value.clock?.takeIf { it.bookId == bookId }?.stoppedAt(now)
        val inTrack = position.positionInTrackMs
        val inBook = timeline?.toBookPosition(position.trackIndex, inTrack) ?: inTrack
        BookClock(bookId, inBook, timeline?.durationMs ?: 0, position.speed, running, now)
    }


    override fun onNowPlayingChanged(nowPlaying: NowPlaying?) {
        // If the service ends or the queue empties, the last state stays, just paused.
        this.nowPlaying.value = nowPlaying ?: this.nowPlaying.value?.copy(isPlaying = false)
    }

    private suspend fun update(nowPlaying: NowPlaying?) {
        // Without a report or remembered state there is nothing to show.
        nowPlaying ?: return
        val area = nowPlaying.area
        val entry = entry(nowPlaying, _state.value.entries[area])
        // On every update: grants don't survive a device restart.
        entry.coverUri?.let(::grantCoverAccess)
        _state.update { state ->
            val now = System.currentTimeMillis()
            val entries = state.entries.toMutableMap()
            // If another area comes into the player, the previous one stays paused with its playing time stopped.
            val left = state.currentArea?.takeIf { it != area }
            left?.let(entries::get)?.let { old ->
                val clock = state.clock?.takeIf { it.bookId == old.nowPlaying.bookId } ?: old.clock
                entries[left] = old.copy(
                    nowPlaying = old.nowPlaying.copy(isPlaying = false),
                    clock = clock?.stoppedAt(SystemClock.elapsedRealtime()),
                    lastActiveAt = now,
                )
            }
            entries[area] = entry.copy(lastActiveAt = now)
            // The playing time comes from the own tick; it stays until the next value is there. The current state is only
            // read now, because it can change above during loading.
            val bookId = nowPlaying.bookId
            state.copy(
                entries = entries,
                currentArea = area,
                clock = state.clock?.takeIf { bookId != null && it.bookId == bookId } ?: entry.clock,
            )
        }
        NowPlayingWidget().updateAll(context)
        revokeUnusedCovers()
    }

    /**
     * Entry for [nowPlaying] including cover; [previous] is the area's previous one (with the same album or book its
     * cover stays).
     */
    private suspend fun entry(nowPlaying: NowPlaying, previous: WidgetEntry?): WidgetEntry {
        val albumId = nowPlaying.albumId
        val bookId = nowPlaying.bookId
        val sameCover = previous != null && previous.coverUri != null && when {
            bookId != null -> bookId == previous.nowPlaying.bookId
            albumId != null -> albumId == previous.nowPlaying.albumId
            else -> false
        }
        // The image itself is only loaded for aspect ratio and color; the launcher loads it for display.
        val cover = when {
            sameCover -> null
            bookId != null -> bookCovers.cover(bookId)?.scaledToWidget()
            albumId != null -> artworkRepository.albumCover(albumId, COVER_SIZE_PX)
            else -> null
        }
        val coverUri = when {
            sameCover -> previous.coverUri
            cover == null -> null
            bookId != null -> CoverProvider.bookCoverUri(context, bookId).versioned()
            else -> albumId?.let { CoverProvider.albumCoverUri(context, it).versioned() }
        }
        val aspectRatio = if (sameCover) previous.coverAspectRatio else cover?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
        val seedColor = when {
            sameCover && previous.seedColor != null -> previous.seedColor
            bookId != null -> cover?.let { withContext(Dispatchers.Default) { seedColorOf(it) } }
            albumId != null -> albumColorRepository.seedColorNow(albumId)
            else -> null
        }
        // The area's stopped playing time, until the tick reports a new one.
        val clock = previous?.clock?.takeIf { bookId != null && it.bookId == bookId }
        return WidgetEntry(nowPlaying, coverUri, aspectRatio, seedColor, clock, previous?.lastActiveAt ?: 0)
    }

    /**
     * The [CoverProvider] isn't exported: the home screen app that shows the widget gets read permission for exactly
     * this cover (no prefix grant as for Android Auto). The app can't see other widget hosts; there the
     * cover would stay empty.
     */
    private fun grantCoverAccess(uri: Uri) {
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" } // Chooser dialog, no default app
            ?: return
        try {
            context.grantUriPermission(home, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            grantedCovers[uri] = home
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cover not granted to $home", e)
        }
    }

    /** Covers granted by [grantCoverAccess], with the launcher, so that those no longer shown are taken back (review2 J87). */
    private val grantedCovers = ConcurrentHashMap<Uri, String>()

    private fun revokeUnusedCovers() {
        val shown = _state.value.entries.values.mapNotNullTo(HashSet()) { it.coverUri }
        grantedCovers.keys.filter { it !in shown }.forEach { uri ->
            val home = grantedCovers.remove(uri) ?: return@forEach
            try {
                context.revokeUriPermission(home, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cover not revoked from $home", e)
            }
        }
    }

    /**
     * A separate URI per process (the provider only reads the path): an `ImageView` doesn't load the same URI again. After
     * a device restart the launcher shows the widget before the app has renewed the grant; without a new URI the
     * cover would then stay empty.
     */
    private fun Uri.versioned(): Uri = buildUpon().appendQueryParameter("v", coverVersion).build()

    /** Book covers come at 800 px; [COVER_SIZE_PX] is enough for aspect ratio and color. */
    private fun Bitmap.scaledToWidget(): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= COVER_SIZE_PX) return this
        val factor = COVER_SIZE_PX.toFloat() / longest
        return scale((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
    }

    private companion object {
        const val TAG = "WidgetUpdater"
        const val UPDATE_THROTTLE_MS = 300L
        const val TICK_INTERVAL_MS = 10_000L
        const val REMEASURE_MS = 60_000L
        const val COVER_SIZE_PX = 256
    }
}

private const val SECOND_MS = 1_000L
private const val HOUR_MS = 3_600_000L

/** From here the chronometer shows one more hour digit and needs a different [chronometerPrefix]. */
private val PREFIX_CHANGES_MS = listOf(HOUR_MS, 10 * HOUR_MS)

/** Wake up a little after the change, so it has definitely been reached. */
private const val TICK_SLACK_MS = 20L
private const val MIN_SPEED = 0.1f
private const val SPEED_TOLERANCE = 0.001f

/** "00:45:12": hours at least two digits, started seconds truncated. */
internal fun clockText(ms: Long): String {
    val seconds = (ms / SECOND_MS).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

/** "00:45:12 / 03:12:01"; if the length is unknown (0), only the position. */
internal fun bookClockText(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0) "${clockText(positionMs)} / ${clockText(durationMs)}" else clockText(positionMs)

/**
 * Prefix before the chronometer's text so it looks like [clockText]: that shows "45:12" below one hour and
 * "3:12:01" from one hour (`DateUtils.formatElapsedTime`). If the prefix changes, the updater re-measures.
 */
internal fun chronometerPrefix(positionMs: Long): String = when {
    positionMs < HOUR_MS -> "00:"
    positionMs < 10 * HOUR_MS -> "0"
    else -> ""
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WidgetModule {
    @Binds
    @IntoSet
    fun bindNowPlayingObserver(updater: NowPlayingWidgetUpdater): NowPlayingObserver
}
