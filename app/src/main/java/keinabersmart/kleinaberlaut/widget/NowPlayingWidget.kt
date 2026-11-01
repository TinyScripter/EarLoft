package keinabersmart.kleinaberlaut.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.MainActivity
import keinabersmart.kleinaberlaut.R
import com.materialkolor.dynamicColorScheme
import keinabersmart.kleinaberlaut.core.playback.EXTRA_OPEN_NOW_PLAYING
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Widget 4 × 1: Cover links vollflächig & ungeschnitten; rechts Titel, Künstler und Steuerung zentriert. */
class NowPlayingWidget : GlanceAppWidget() {

    // Die tatsächliche Größe wird gebraucht, damit das Cover genau die Höhe füllt (siehe [NowPlayingContent]).
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val updater = context.widgetEntryPoint().widgetUpdater()
        provideContent {
            val state by updater.state.collectAsState()
            GlanceTheme(colors = state.seedColor?.let(::albumColorProviders) ?: GlanceTheme.colors) {
                NowPlayingContent(state)
            }
        }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

private fun albumColorProviders(seedColor: Int): ColorProviders {
    val seed = Color(seedColor)
    return material3ColorProviders(
        light = dynamicColorScheme(seed, isDark = false, isAmoled = false),
        dark = dynamicColorScheme(seed, isDark = true, isAmoled = false),
    )
}

private val WIDGET_PADDING = 8.dp
private val MIN_COVER = 32.dp

@Composable
private fun NowPlayingContent(state: WidgetState) {
    val context = LocalContext.current
    val nowPlaying = state.nowPlaying
    val isPlaying = nowPlaying?.isPlaying == true

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(openAppAction(showNowPlaying = nowPlaying != null))
            .padding(WIDGET_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Cover: füllt die volle Höhe, die Breite folgt dem Seitenverhältnis (Album quadratisch, Buch hochkant), unverzerrt
        // und ohne Leerraum links. Ohne feste Breite würde ein quadratisches Cover in einem zu breiten Feld mittig stehen.
        val coverHeight = (LocalSize.current.height - WIDGET_PADDING * 2).coerceAtLeast(MIN_COVER)
        val cover = state.cover
        val coverWidth = if (cover != null && cover.height > 0) coverHeight * (cover.width.toFloat() / cover.height) else coverHeight
        Image(
            provider = cover?.let { ImageProvider(it) } ?: ImageProvider(DesignR.drawable.ic_music_note),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = if (cover == null) ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant) else null,
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

            // Untertitel: bei Musik der Künstler, bei Büchern und Folgen die Spielzeit (statt Autor bzw. Abo)
            val subtitle = when {
                nowPlaying == null -> null
                nowPlaying.isBook -> state.clock?.let { bookClockText(it.positionMs, it.durationMs) }
                else -> nowPlaying.artist
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    ),
                    modifier = GlanceModifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = GlanceModifier.defaultWeight())

            // 3. Wiedergabe-Steuerung
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (nowPlaying != null) {
                    // Bei Büchern −30 s / +10 s statt Titelsprung (wie im Buch-Player und in der Benachrichtigung).
                    val isBook = nowPlaying.isBook
                    ControlButton(
                        icon = if (isBook) DesignR.drawable.ic_replay else DesignR.drawable.ic_skip_previous,
                        label = context.getString(if (isBook) R.string.spoken_seek_back_30 else R.string.action_previous),
                        action = playerCommand(if (isBook) WidgetPlayerAction.SKIP_BACK else WidgetPlayerAction.PREVIOUS),
                        isPrimary = false,
                        badge = if (isBook) "30" else null,
                    )
                    Spacer(modifier = GlanceModifier.width(12.dp))
                }

                ControlButton(
                    icon = if (isPlaying) DesignR.drawable.ic_pause else DesignR.drawable.ic_play,
                    label = context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                    action = if (isPlaying) playerCommand(WidgetPlayerAction.PAUSE) else actionSendBroadcast(playIntent(context)),
                    isPrimary = true
                )

                if (nowPlaying != null) {
                    Spacer(modifier = GlanceModifier.width(12.dp))
                    val isBook = nowPlaying.isBook
                    ControlButton(
                        icon = if (isBook) DesignR.drawable.ic_forward else DesignR.drawable.ic_skip_next,
                        label = context.getString(if (isBook) R.string.spoken_seek_forward_10 else R.string.action_next),
                        action = playerCommand(if (isBook) WidgetPlayerAction.SKIP_FORWARD else WidgetPlayerAction.NEXT),
                        isPrimary = false,
                        badge = if (isBook) "10" else null,
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

/** „00:23:40 / 01:02:43“; ist die Gesamtlänge unbekannt (0), nur die Position. */
internal fun bookClockText(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0) "${clockOf(positionMs)} / ${clockOf(durationMs)}" else clockOf(positionMs)

private fun clockOf(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
}

private fun playerCommand(command: String): Action =
    actionRunCallback<WidgetPlayerAction>(actionParametersOf(WidgetPlayerAction.CommandKey to command))

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
        .setData("kal://widget/media_button/${KeyEvent.KEYCODE_MEDIA_PLAY}".toUri())
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
        }
    }

    companion object {
        val CommandKey = ActionParameters.Key<String>("command")
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