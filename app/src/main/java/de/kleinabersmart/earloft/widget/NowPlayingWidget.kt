package de.kleinabersmart.earloft.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.ImageProvider
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.datastore.preferences.core.Preferences
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as material3ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.MainActivity
import de.kleinabersmart.earloft.R
import com.materialkolor.dynamicColorScheme
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.EXTRA_OPEN_NOW_PLAYING
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.designsystem.theme.appColorScheme
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import de.kleinabersmart.earloft.core.playback.R as PlaybackR

/**
 * Widget 4 × 1: Cover links vollflächig & ungeschnitten; rechts Titel, Künstler und Steuerung zentriert. Jedes Widget hat
 * eigene Bereiche und Farben ([WidgetConfig], beim Anlegen in [WidgetConfigActivity] gewählt, im Glance-Zustand des
 * Widgets).
 */
class NowPlayingWidget : GlanceAppWidget() {

    // Die tatsächliche Größe wird gebraucht, damit das Cover genau die Höhe füllt (siehe [NowPlayingContent]).
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val updater = context.widgetEntryPoint().widgetUpdater()
        updater.onWidgetShown()
        provideContent {
            val state by updater.state.collectAsState()
            // Noch nicht eingestellt (der Updater holt das für alte Widgets nach): alle Bereiche, Coverfarben.
            val config = currentState<Preferences>().widgetConfig() ?: WidgetConfig()
            val entry = state.entryFor(config.areas)
            GlanceTheme(colors = widgetColors(entry, config.colors)) {
                NowPlayingContent(state, entry, config.areas)
            }
        }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()

    /** Das letzte Widget ist weg: Die Spielzeit muss nicht mehr mitlaufen. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.widgetEntryPoint().widgetUpdater().onAllWidgetsRemoved()
    }
}

/**
 * Nach dem Cover, wenn so eingestellt und es eine Farbe hat; sonst die App-Farben aus dem Logo bzw. die Systemfarben
 * (Standard von Glance, ab Android 12; davor ebenfalls die App-Farben). Ohne Coverfarbe gelten bei „Cover“ die App-Farben.
 */
@Composable
private fun widgetColors(entry: WidgetEntry?, colors: WidgetColors): ColorProviders {
    val seedColor = entry?.seedColor?.takeIf { colors == WidgetColors.COVER }
    return when {
        seedColor != null -> remember(seedColor) { albumColorProviders(seedColor) }
        colors == WidgetColors.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlanceTheme.colors
        else -> remember { appColorProviders() }
    }
}

private fun appColorProviders(): ColorProviders = material3ColorProviders(
    light = appColorScheme(isDark = false),
    dark = appColorScheme(isDark = true),
)

private fun albumColorProviders(seedColor: Int): ColorProviders {
    val seed = Color(seedColor)
    return material3ColorProviders(
        light = dynamicColorScheme(seed, isDark = false, isAmoled = false),
        dark = dynamicColorScheme(seed, isDark = true, isAmoled = false),
    )
}

private val WIDGET_PADDING = 8.dp
private val MIN_COVER = 32.dp

/**
 * Darunter passen Titel, Kapitel, Spielzeit und Knöpfe nicht mehr übereinander; das Kapitel entfällt dann. Das 4×1-Widget ist am
 * Pixel 10 Pro etwa 100 dp hoch; wer das Kapitel sehen will, zieht es eine Zeile höher (M4).
 */
private val CHAPTER_MIN_HEIGHT = 104.dp

/**
 * [entry]: was das Widget für seine Bereiche zeigt. Steht es nicht im Player (dort läuft ein anderer Bereich), gibt es nur
 * „Abspielen“, und das setzt es erst fort; die übrigen Knöpfe wirkten sonst auf das Laufende. Lief aus [areas] noch nie
 * etwas, startet „Abspielen“ wie eine Medientaste, was zuletzt lief – nur, wenn das Widget für alle Bereiche gilt; sonst
 * öffnet es die App.
 */
@Composable
private fun NowPlayingContent(state: WidgetState, entry: WidgetEntry?, areas: Set<SpokenMode>) {
    val context = LocalContext.current
    val nowPlaying = entry?.nowPlaying
    val isCurrent = entry != null && state.isCurrent(entry)
    val isPlaying = isCurrent && nowPlaying?.isPlaying == true
    // Ohne Steuerung des Laufenden: Musik, Buch bzw. Folge dieses Widgets erst wieder laden.
    val controls = nowPlaying?.takeIf { isCurrent }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(openAppAction(showNowPlaying = isCurrent))
            .padding(WIDGET_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Cover: füllt die volle Höhe, die Breite folgt dem Seitenverhältnis (Album quadratisch, Buch hochkant), unverzerrt
        // und ohne Leerraum links. Ohne feste Breite würde ein quadratisches Cover in einem zu breiten Feld mittig stehen.
        val coverHeight = (LocalSize.current.height - WIDGET_PADDING * 2).coerceAtLeast(MIN_COVER)
        val coverUri = entry?.coverUri
        val coverWidth = if (coverUri != null) coverHeight * entry.coverAspectRatio else coverHeight
        Image(
            provider = coverUri?.let { ImageProvider(it) } ?: ImageProvider(DesignR.drawable.ic_music_note),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = if (coverUri == null) ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant) else null,
            modifier = GlanceModifier
                .size(width = coverWidth, height = coverHeight)
                .cornerRadius(12.dp),
        )

        // 2. Rechter Bereich: Zentrierter Text & Steuerung
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .padding(start = 12.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Track-Titel
            Text(
                text = nowPlaying?.title ?: context.getString(R.string.player_nothing_playing),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
            )

            // Bei Büchern und Folgen mit Kapiteln das laufende Kapitel, sofern das Widget dafür hoch genug ist.
            val chapter = nowPlaying?.chapter?.takeIf { nowPlaying.isBook && LocalSize.current.height >= CHAPTER_MIN_HEIGHT }
            if (chapter != null) Subtitle(chapter)

            // Untertitel: bei Musik der Künstler, bei Büchern und Folgen die Spielzeit (statt Autor bzw. Abo); solange sie
            // nicht feststeht, der Autor.
            val clock = entry?.clock?.takeIf { nowPlaying?.isBook == true }
            if (clock != null) {
                BookClockLine(clock)
            } else if (nowPlaying != null) {
                Subtitle(nowPlaying.artist)
            }

            Spacer(modifier = GlanceModifier.defaultWeight())

            // 3. Wiedergabe-Steuerung
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (controls != null) {
                    // Bei Büchern und Folgen Spulen statt Titelsprung (wie im Buch-Player und in der Benachrichtigung).
                    val isBook = controls.isBook
                    val back = state.seekOf(controls).backSeconds
                    ControlButton(
                        icon = if (isBook) DesignR.drawable.ic_replay else DesignR.drawable.ic_skip_previous,
                        label = if (isBook) {
                            context.resources.getQuantityString(PlaybackR.plurals.spoken_seek_back, back, back)
                        } else {
                            context.getString(R.string.action_previous)
                        },
                        action = playerCommand(if (isBook) WidgetPlayerAction.SKIP_BACK else WidgetPlayerAction.PREVIOUS),
                        isPrimary = false,
                        badge = if (isBook) back.toString() else null,
                    )
                    Spacer(modifier = GlanceModifier.width(12.dp))
                }

                ControlButton(
                    icon = if (isPlaying) DesignR.drawable.ic_pause else DesignR.drawable.ic_play,
                    label = context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                    action = when {
                        isPlaying -> playerCommand(WidgetPlayerAction.PAUSE)
                        nowPlaying != null && !isCurrent -> resumeAction(nowPlaying.bookId)
                        nowPlaying == null && !areas.containsAll(SpokenMode.entries) -> openAppAction(showNowPlaying = false)
                        else -> actionSendBroadcast(playIntent(context))
                    },
                    isPrimary = true
                )

                if (controls != null) {
                    Spacer(modifier = GlanceModifier.width(12.dp))
                    val isBook = controls.isBook
                    val forward = state.seekOf(controls).forwardSeconds
                    ControlButton(
                        icon = if (isBook) DesignR.drawable.ic_forward else DesignR.drawable.ic_skip_next,
                        label = if (isBook) {
                            context.resources.getQuantityString(PlaybackR.plurals.spoken_seek_forward, forward, forward)
                        } else {
                            context.getString(R.string.action_next)
                        },
                        action = playerCommand(if (isBook) WidgetPlayerAction.SKIP_FORWARD else WidgetPlayerAction.NEXT),
                        isPrimary = false,
                        badge = if (isBook) forward.toString() else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: Int,
    label: String,
    action: Action,
    isPrimary: Boolean = false,
    /** Zahl im Symbol (Sprungweite in Sekunden bei Büchern). */
    badge: String? = null,
) {
    if (isPrimary) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .size(36.dp)
                .cornerRadius(18.dp)
                .background(GlanceTheme.colors.primary)
                .clickable(action)
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                modifier = GlanceModifier.size(20.dp)
            )
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .size(32.dp)
                .cornerRadius(16.dp)
                .clickable(action)
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.size(if (badge != null) 28.dp else 20.dp)
            )
            if (badge != null) {
                Text(
                    text = badge,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private val SUBTITLE_TEXT_SIZE = 12.sp
private val SUBTITLE_PADDING = 2.dp

@Composable
private fun Subtitle(text: String) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = SUBTITLE_TEXT_SIZE,
            textAlign = TextAlign.Center
        ),
        modifier = GlanceModifier.padding(top = SUBTITLE_PADDING)
    )
}

/**
 * „00:45:12 / 03:12:01“ (gespielt / gesamt). Läuft das Buch bei 1×, zählt ein Chronometer im Launcher selbst hoch, und
 * zwar nur, solange das Widget zu sehen ist; die App sendet dafür nichts. Sonst fester Text: pausiert, weil ein
 * angehaltener Chronometer beim erneuten Aufbau durch den Launcher von seiner Basis aus neu rechnete; bei anderem Tempo,
 * weil er nur in Echtzeit zählt (dann setzt der Updater die Zeit jede Sekunde neu).
 */
@Composable
private fun BookClockLine(clock: BookClock) {
    val context = LocalContext.current
    if (!clock.ticksItself) {
        Subtitle(bookClockText(clock.positionMs, clock.durationMs))
        return
    }
    val color = GlanceTheme.colors.onSurfaceVariant
    val total = if (clock.durationMs > 0) " / ${clockText(clock.durationMs)}" else ""
    val views = RemoteViews(context.packageName, R.layout.widget_book_clock).apply {
        val id = R.id.widget_book_clock
        setChronometer(id, clock.measuredAtMs - clock.positionMs, "${chronometerPrefix(clock.positionMs)}%s$total", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Wie bei Glance selbst: Hell/Dunkel wechselt der Launcher, ohne dass das Widget neu gebaut wird.
            setColorInt(id, "setTextColor", color.argb(context, night = false), color.argb(context, night = true))
        } else {
            setTextColor(id, color.getColor(context).toArgb())
        }
    }
    // Ohne Größe füllt der Rahmen, den Glance um die RemoteViews legt, die ganze Spalte.
    AndroidRemoteViews(views, GlanceModifier.wrapContentSize().padding(top = SUBTITLE_PADDING))
}

private fun ColorProvider.argb(context: Context, night: Boolean): Int {
    val configuration = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return getColor(context.createConfigurationContext(configuration)).toArgb()
}

private fun playerCommand(command: String): Action =
    actionRunCallback<WidgetPlayerAction>(actionParametersOf(WidgetPlayerAction.CommandKey to command))

/** Das Buch bzw. die Folge [bookId] an der gespeicherten Stelle, ohne: die gespeicherte Musik-Warteschlange. */
private fun resumeAction(bookId: Long?): Action =
    if (bookId == null) {
        playerCommand(WidgetPlayerAction.RESUME_MUSIC)
    } else {
        actionRunCallback<WidgetPlayerAction>(
            actionParametersOf(WidgetPlayerAction.CommandKey to WidgetPlayerAction.RESUME_BOOK, WidgetPlayerAction.BookIdKey to bookId),
        )
    }

@OptIn(UnstableApi::class)
private fun openAppAction(showNowPlaying: Boolean): Action {
    if (!showNowPlaying) return actionStartActivity<MainActivity>()
    return actionStartActivity<MainActivity>(actionParametersOf(OPEN_NOW_PLAYING_KEY to true))
}

private val OPEN_NOW_PLAYING_KEY = ActionParameters.Key<Boolean>(EXTRA_OPEN_NOW_PLAYING)

@OptIn(UnstableApi::class)
private fun playIntent(context: Context): Intent =
    Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
        .setData("earloft://widget/media_button/${KeyEvent.KEYCODE_MEDIA_PLAY}".toUri())
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))

/** Steuert den laufenden Service über die [PlaybackConnection]. */
class WidgetPlayerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val connection = context.widgetEntryPoint().playbackConnection()
        when (parameters[CommandKey]) {
            PAUSE -> connection.pause()
            NEXT -> connection.next()
            PREVIOUS -> connection.previous()
            SKIP_BACK -> connection.skipBack()
            SKIP_FORWARD -> connection.skipForward()
            RESUME_MUSIC -> connection.resumeMusic()
            RESUME_BOOK -> parameters[BookIdKey]?.let { connection.playBook(it) }
        }
    }

    companion object {
        val CommandKey = ActionParameters.Key<String>("command")
        val BookIdKey = ActionParameters.Key<Long>("book_id")
        const val RESUME_MUSIC = "resume_music"
        const val RESUME_BOOK = "resume_book"
        const val PAUSE = "pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val SKIP_BACK = "skip_back"
        const val SKIP_FORWARD = "skip_forward"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun widgetUpdater(): NowPlayingWidgetUpdater
    fun playbackConnection(): PlaybackConnection
}

private fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)