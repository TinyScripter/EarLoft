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
 * Widget 4 × 1: cover on the left, full-bleed and uncropped; on the right title, artist and controls centered. Each widget has
 * its own areas and colors ([WidgetConfig], chosen when created in [WidgetConfigActivity], in the widget's Glance
 * state).
 */
class NowPlayingWidget : GlanceAppWidget() {

    // The actual size is needed so the cover fills exactly the height (see [NowPlayingContent]).
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val updater = context.widgetEntryPoint().widgetUpdater()
        updater.onWidgetShown()
        provideContent {
            val state by updater.state.collectAsState()
            // Not configured yet (the updater catches up on this for old widgets): all areas, cover colors.
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

    /** The last widget is gone: the playing time no longer needs to keep running. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.widgetEntryPoint().widgetUpdater().onAllWidgetsRemoved()
    }
}

/**
 * Based on the cover, if configured so and it has a color; otherwise the app colors from the logo or the system colors
 * (Glance default, from Android 12; before that also the app colors). Without a cover color, "Cover" uses the app colors.
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
 * Below this, title, chapter, playing time and buttons no longer fit on top of each other; the chapter is then omitted. The 4×1 widget is
 * about 100 dp tall on the Pixel 10 Pro; anyone who wants to see the chapter drags it one row taller (M4).
 */
private val CHAPTER_MIN_HEIGHT = 104.dp

/**
 * From this height the title may have two or three lines (one line about 20 dp; plus chapter, subtitle and buttons).
 * In the enlarged widget the text column is narrow because the cover fills the height; with one line little was left of the title.
 */
private val TITLE_TWO_LINES_MIN_HEIGHT = 140.dp
private val TITLE_THREE_LINES_MIN_HEIGHT = 180.dp

/**
 * [entry]: what the widget shows for its areas. If it isn't in the player (another area is playing there), there is only
 * "Play", which resumes it first; the other buttons would otherwise act on what is playing. If nothing from [areas] has ever
 * played, "Play" starts what played last, like a media button – only if the widget applies to all areas; otherwise
 * it opens the app.
 */
@Composable
private fun NowPlayingContent(state: WidgetState, entry: WidgetEntry?, areas: Set<SpokenMode>) {
    val context = LocalContext.current
    val nowPlaying = entry?.nowPlaying
    val isCurrent = entry != null && state.isCurrent(entry)
    val isPlaying = isCurrent && nowPlaying?.isPlaying == true
    // Without controlling what is playing: reload this widget's music, book or episode first.
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
        // 1. Cover: fills the full height, the width follows the aspect ratio (album square, book portrait), undistorted
        // and without empty space on the left. Without a fixed width a square cover would sit centered in a field that's too wide.
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

        // 2. Right area: centered text & controls
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .padding(start = 12.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Track title
            Text(
                text = nowPlaying?.title ?: context.getString(R.string.player_nothing_playing),
                maxLines = when {
                    LocalSize.current.height >= TITLE_THREE_LINES_MIN_HEIGHT -> 3
                    LocalSize.current.height >= TITLE_TWO_LINES_MIN_HEIGHT -> 2
                    else -> 1
                },
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
            )

            // For books and episodes with chapters, the current chapter, if the widget is tall enough for it.
            val chapter = nowPlaying?.chapter?.takeIf { nowPlaying.isBook && LocalSize.current.height >= CHAPTER_MIN_HEIGHT }
            if (chapter != null) Subtitle(chapter)

            // Subtitle: for music the artist, for books and episodes the playing time (instead of author or subscription); as long as it
            // isn't known, the author.
            val clock = entry?.clock?.takeIf { nowPlaying?.isBook == true }
            if (clock != null) {
                BookClockLine(clock)
            } else if (nowPlaying != null) {
                Subtitle(nowPlaying.artist)
            }

            Spacer(modifier = GlanceModifier.defaultWeight())

            // 3. Playback controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (controls != null) {
                    // For books and episodes, seeking instead of track skipping (as in the book player and in the notification).
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
    /** Number in the icon (skip interval in seconds for books). */
    badge: String? = null,
) {
    // A touch target of 48 dp, the visible circle inside (review2 J84); the space around it replaces the gap between the buttons.
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier
            .size(TOUCH_TARGET)
            .cornerRadius(TOUCH_TARGET / 2)
            .clickable(action),
    ) {
        if (isPrimary) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier
                    .size(36.dp)
                    .cornerRadius(18.dp)
                    .background(GlanceTheme.colors.primary),
            ) {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                    modifier = GlanceModifier.size(20.dp),
                )
            }
        } else {
            Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(32.dp)) {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier.size(if (badge != null) 28.dp else 20.dp),
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
}

private val TOUCH_TARGET = 48.dp

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
 * "00:45:12 / 03:12:01" (played / total). If the book plays at 1×, a chronometer in the launcher counts up by itself, and
 * only while the widget is visible; the app sends nothing for it. Otherwise a fixed text: when paused, because a
 * stopped chronometer would recompute from its base when the launcher rebuilds it; at other speeds,
 * because it only counts in real time (then the updater sets the time every 10 s, see `NowPlayingWidgetUpdater`).
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
            // As with Glance itself: the launcher switches light/dark without the widget being rebuilt.
            setColorInt(id, "setTextColor", color.argb(context, night = false), color.argb(context, night = true))
        } else {
            setTextColor(id, color.getColor(context).toArgb())
        }
    }
    // Without a size, the frame that Glance puts around the RemoteViews fills the whole column.
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

/** The book or episode [bookId] at the stored position; without it: the stored music queue. */
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

/** Controls the running service via the [PlaybackConnection]. */
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