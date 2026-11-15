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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Spielzeit eines Buchs bzw. einer Folge: Position und Länge in Buchzeit (Länge 0 = unbekannt) und das Tempo, gemessen zu
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
    /** Zählt das Widget selbst (Chronometer im Launcher)? Nur bei 1×, denn der Chronometer läuft in Echtzeit. */
    val ticksItself: Boolean get() = running && abs(speed - 1f) < SPEED_TOLERANCE

    /** Stand zum Zeitpunkt [nowMs]: Läuft sie, ist seit der Messung Buchzeit vergangen (Echtzeit mal Tempo). */
    fun at(nowMs: Long): BookClock {
        if (!running) return this
        val position = positionMs + ((nowMs - measuredAtMs) * speed).toLong()
        return copy(positionMs = if (durationMs > 0) position.coerceAtMost(durationMs) else position, measuredAtMs = nowMs)
    }

    /** Angehalten zum Zeitpunkt [nowMs]. */
    fun stoppedAt(nowMs: Long): BookClock = if (running) at(nowMs).copy(running = false) else this

    /** Echtzeit, bis die Stunden eine Stelle dazubekommen und der Chronometer einen anderen Vorsatz braucht; `null` = nie. */
    fun msUntilPrefixChange(): Long? {
        val next = PREFIX_CHANGES_MS.firstOrNull { it > positionMs } ?: return null
        return untilBookTime(next - positionMs)
    }

    private fun untilBookTime(bookMs: Long): Long = (bookMs / speed.coerceAtLeast(MIN_SPEED)).toLong() + TICK_SLACK_MS
}

/** Was die Widgets für einen Bereich zeigen: das Laufende bzw. das zuletzt Gehörte daraus. */
data class WidgetEntry(
    val nowPlaying: NowPlaying,
    /**
     * Cover über den [CoverProvider]; `null` = keins. Der Launcher lädt es selbst, statt es mit jeder Aktualisierung als
     * Bild zu bekommen (bei Tempo ≠ 1× alle 10 s).
     */
    val coverUri: Uri? = null,
    /** Breite durch Höhe des Covers (Album quadratisch, Buch meist hochkant). */
    val coverAspectRatio: Float = 1f,
    /** Grundfarbe des Covers (ARGB) für die Widget-Farben; `null` = App-Farben. */
    val seedColor: Int? = null,
    /** Spielzeit bei Büchern und Folgen (angehalten); für den Bereich im Player gilt [WidgetState.clock]. */
    val clock: BookClock? = null,
    /** Wann der Bereich zuletzt im Player war (Wanduhr). */
    val lastActiveAt: Long = 0,
)

data class WidgetState(
    /** Je Bereich das Laufende bzw. zuletzt Gehörte. */
    val entries: Map<SpokenMode, WidgetEntry> = emptyMap(),
    /** Der Bereich dessen, was im Player steht bzw. zuletzt stand. */
    val currentArea: SpokenMode? = null,
    /** Spielzeit des Buchs bzw. der Folge im Player; `null` bei Musik oder solange sie nicht feststeht. */
    val clock: BookClock? = null,
    /** Sprungweiten aus den Einstellungen, für Bücher bzw. Folgen. */
    val bookSeek: SeekSteps = SeekSteps.DEFAULT,
    val podcastSeek: SeekSteps = SeekSteps.DEFAULT,
) {
    /**
     * Was ein Widget für [areas] zeigt: das, was im Player steht, wenn es dazugehört, sonst das zuletzt Gehörte daraus
     * (pausiert); `null`, wenn aus diesen Bereichen noch nie etwas lief.
     */
    fun entryFor(areas: Set<SpokenMode>): WidgetEntry? {
        val current = currentArea?.takeIf { it in areas }?.let(entries::get)
        if (current != null) return current.copy(clock = clock?.takeIf { it.bookId == current.nowPlaying.bookId } ?: current.clock)
        return entries.filterKeys { it in areas }.values.maxByOrNull { it.lastActiveAt }
    }

    /**
     * Neue Spielzeit im Player. Gehörte die bisherige zu einem anderen Buch bzw. einer Folge (der Takt meldet den Wechsel
     * vor dem neuen Eintrag), bleibt sie dort angehalten zum Zeitpunkt [nowMs] stehen.
     */
    fun withClock(clock: BookClock?, nowMs: Long): WidgetState {
        val previous = this.clock?.takeIf { it.bookId != clock?.bookId } ?: return copy(clock = clock)
        val stopped = previous.stoppedAt(nowMs)
        return copy(
            clock = clock,
            entries = entries.mapValues { (_, entry) -> if (entry.nowPlaying.bookId == previous.bookId) entry.copy(clock = stopped) else entry },
        )
    }

    /** Steht [entry] im Player? Sonst setzt „Abspielen“ ihn erst wieder fort. */
    fun isCurrent(entry: WidgetEntry): Boolean = entry.nowPlaying.area == currentArea

    /** Sprungweiten für ein Buch bzw. eine Folge. */
    fun seekOf(nowPlaying: NowPlaying): SeekSteps = if (nowPlaying.isEpisode) podcastSeek else bookSeek
}

/**
 * Hält den Zustand für das Widget und aktualisiert es gedrosselt. Der letzte Stand bleibt stehen, auch wenn der Service
 * endet, und überdauert den Prozess ([WidgetStateStore]). Wird vom PlaybackService auch dann benachrichtigt, wenn die
 * App-UI nicht läuft.
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

    /** Ist mindestens ein Widget platziert? Ohne Widget wird keine Spielzeit gemessen. */
    private val widgetsPlaced = MutableStateFlow(false)

    init {
        scope.launch {
            if (GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) widgetsPlaced.value = true
        }
        scope.launch { configureLegacyWidgets(themeSettings) }
        scope.launch {
            restore()
            // Erst nach dem Laden speichern, sonst überschriebe der leere Anfangszustand den gemerkten. Nur mit angehaltener
            // Uhr, damit der Takt bei Tempo ≠ 1× nicht alle 10 s schreibt; Pause und Ende des Service halten sie an.
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

    /** Ein Widget wird angezeigt (Glance baut es auf). */
    fun onWidgetShown() {
        widgetsPlaced.value = true
    }

    /** Das letzte Widget wurde entfernt. */
    fun onAllWidgetsRemoved() {
        widgetsPlaced.value = false
    }

    /**
     * Widgets aus der Zeit vor den Einstellungen je Widget (oder von einem Launcher, der den Dialog beim Anlegen übergeht):
     * alle Bereiche, Farben wie der frühere Schalter „Coverfarben im Widget“.
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
     * Nach dem Prozessstart den gemerkten Stand je Bereich zeigen; was der Service inzwischen gemeldet hat, geht vor. Im
     * Player steht danach der zuletzt gehörte Bereich.
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

    /** Was für den Bereich von [entry] gespeichert wird: pausiert, mit der Spielzeit, wo sie gerade steht. */
    private fun WidgetState.saved(entry: WidgetEntry): SavedWidget {
        val clock = if (isCurrent(entry)) clock?.takeIf { it.bookId == entry.nowPlaying.bookId } ?: entry.clock else entry.clock
        return SavedWidget(entry.nowPlaying.copy(isPlaying = false), clock?.positionMs, clock?.durationMs, entry.lastActiveAt)
    }

    /**
     * Die Spielzeit eines Buchs bzw. einer Folge: gemessen beim Start, bei Pause, beim Spulen und bei Tempowechseln
     * ([measured]); dazwischen läuft sie im Sekundentakt weiter.
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
     * Eine Messung und, solange es läuft, der Takt danach. Bei 1× zählt der Chronometer im Launcher selbst, und zwar nur,
     * solange das Widget zu sehen ist; neu gemessen wird nur, wenn die Stunden eine Stelle dazubekommen. Bei anderem Tempo
     * kann er nicht mitzählen (er läuft in Echtzeit): Dann setzt die App die Zeit alle 10 s neu, solange das Widget zu
     * sehen sein kann ([widgetMaybeVisible]).
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
     * Die Spielzeit alle [TICK_INTERVAL_MS] Echtzeit, bei 1,5× springt sie also um 15 Sekunden. Hochgerechnet aus der
     * letzten Messung, denn der Player meldet seine Position in Sprüngen von einigen hundert Millisekunden. Nachgemessen
     * wird jede Minute.
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
     * Kann das Widget gerade zu sehen sein? Bildschirm an und entsperrt, und die App ist nicht selbst im Vordergrund. Ob
     * der Startbildschirm vorn ist, erfährt eine App nicht. Die Empfänger sind nur registriert, solange der Takt läuft.
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

    /** Ohne geladenes Buch (Service beendet, Prozess neu) bleibt die letzte Spielzeit stehen. */
    private suspend fun currentClock(bookId: Long, timeline: BookTimeline?, running: Boolean): BookClock? = withContext(Dispatchers.Main.immediate) {
        val now = SystemClock.elapsedRealtime()
        val position = servicePlayer.bookPosition(bookId)
            ?: return@withContext _state.value.clock?.takeIf { it.bookId == bookId }?.stoppedAt(now)
        val inTrack = position.positionInTrackMs
        val inBook = timeline?.toBookPosition(position.trackIndex, inTrack) ?: inTrack
        BookClock(bookId, inBook, timeline?.durationMs ?: 0, position.speed, running, now)
    }


    override fun onNowPlayingChanged(nowPlaying: NowPlaying?) {
        // Endet der Service oder leert sich die Warteschlange, bleibt der letzte Stand stehen, nur eben pausiert.
        this.nowPlaying.value = nowPlaying ?: this.nowPlaying.value?.copy(isPlaying = false)
    }

    private suspend fun update(nowPlaying: NowPlaying?) {
        // Ohne Meldung und gemerkten Stand gibt es nichts zu zeigen.
        nowPlaying ?: return
        val area = nowPlaying.area
        val entry = entry(nowPlaying, _state.value.entries[area])
        // Bei jeder Aktualisierung: Freigaben überdauern keinen Neustart des Geräts.
        entry.coverUri?.let(::grantCoverAccess)
        _state.update { state ->
            val now = System.currentTimeMillis()
            val entries = state.entries.toMutableMap()
            // Kommt ein anderer Bereich in den Player, bleibt der bisherige pausiert mit angehaltener Spielzeit stehen.
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
            // Die Spielzeit kommt vom eigenen Takt; sie bleibt, bis der nächste Wert da ist. Der aktuelle Stand wird erst
            // jetzt gelesen, weil sie sich während des Ladens oben ändern kann.
            val bookId = nowPlaying.bookId
            state.copy(
                entries = entries,
                currentArea = area,
                clock = state.clock?.takeIf { bookId != null && it.bookId == bookId } ?: entry.clock,
            )
        }
        NowPlayingWidget().updateAll(context)
    }

    /**
     * Eintrag für [nowPlaying] samt Cover; [previous] ist der bisherige des Bereichs (bei gleichem Album bzw. Buch bleibt
     * dessen Cover).
     */
    private suspend fun entry(nowPlaying: NowPlaying, previous: WidgetEntry?): WidgetEntry {
        val albumId = nowPlaying.albumId
        val bookId = nowPlaying.bookId
        val sameCover = previous != null && previous.coverUri != null && when {
            bookId != null -> bookId == previous.nowPlaying.bookId
            albumId != null -> albumId == previous.nowPlaying.albumId
            else -> false
        }
        // Das Bild selbst wird nur noch für Seitenverhältnis und Farbe geladen; anzeigen lädt es der Launcher.
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
        // Die angehaltene Spielzeit des Bereichs, bis der Takt eine neue meldet.
        val clock = previous?.clock?.takeIf { bookId != null && it.bookId == bookId }
        return WidgetEntry(nowPlaying, coverUri, aspectRatio, seedColor, clock, previous?.lastActiveAt ?: 0)
    }

    /**
     * Der [CoverProvider] ist nicht exportiert: Die Startbildschirm-App, die das Widget zeigt, bekommt Leserecht auf genau
     * dieses Cover (keine Präfix-Freigabe wie für Android Auto). Andere Widget-Hosts sieht die App nicht; dort bliebe das
     * Cover leer.
     */
    private fun grantCoverAccess(uri: Uri) {
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" } // Auswahldialog, keine Standard-App
            ?: return
        try {
            context.grantUriPermission(home, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cover für $home nicht freigegeben", e)
        }
    }

    /**
     * Je Prozess eine eigene URI (der Provider liest nur den Pfad): Ein `ImageView` lädt dieselbe URI nicht noch einmal. Nach
     * einem Neustart des Geräts zeigt der Launcher das Widget, bevor die App die Freigabe erneuert hat; ohne neue URI bliebe
     * das Cover dann leer.
     */
    private fun Uri.versioned(): Uri = buildUpon().appendQueryParameter("v", coverVersion).build()

    /** Buchcover kommen mit 800 px; für Seitenverhältnis und Farbe reicht [COVER_SIZE_PX]. */
    private fun Bitmap.scaledToWidget(): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= COVER_SIZE_PX) return this
        val factor = COVER_SIZE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), true)
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

/** Ab hier zeigt der Chronometer eine Stundenstelle mehr und braucht einen anderen [chronometerPrefix]. */
private val PREFIX_CHANGES_MS = listOf(HOUR_MS, 10 * HOUR_MS)

/** Etwas nach dem Wechsel aufwachen, damit er sicher erreicht ist. */
private const val TICK_SLACK_MS = 20L
private const val MIN_SPEED = 0.1f
private const val SPEED_TOLERANCE = 0.001f

/** „00:45:12“: Stunden mindestens zweistellig, angefangene Sekunden abgeschnitten. */
internal fun clockText(ms: Long): String {
    val seconds = (ms / SECOND_MS).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

/** „00:45:12 / 03:12:01“; ist die Länge unbekannt (0), nur die Position. */
internal fun bookClockText(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0) "${clockText(positionMs)} / ${clockText(durationMs)}" else clockText(positionMs)

/**
 * Vorsatz vor den Text des Chronometers, damit er wie [clockText] aussieht: Der zeigt „45:12“ unter einer Stunde und
 * „3:12:01“ ab einer Stunde (`DateUtils.formatElapsedTime`). Wechselt der Vorsatz, misst der Updater neu.
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
