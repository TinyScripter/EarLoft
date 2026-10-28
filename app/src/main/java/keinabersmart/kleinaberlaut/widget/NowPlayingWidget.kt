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
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as material3ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
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
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Widget 4×1: Cover links; rechts Titel, Künstler und darunter die Steuerung. Farben aus dem Cover. */
class NowPlayingWidget : GlanceAppWidget() {

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
            .clickable(actionStartActivity<MainActivity>())
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = state.cover?.let { ImageProvider(it) } ?: ImageProvider(DesignR.drawable.ic_music_note),
            contentDescription = null,
            colorFilter = if (state.cover == null) ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant) else null,
            modifier = GlanceModifier
                .size(COVER_SIZE)
                .cornerRadius(10.dp),
        )
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nowPlaying?.title ?: context.getString(R.string.player_nothing_playing),
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            if (nowPlaying != null) {
                Text(
                    text = nowPlaying.artist,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (nowPlaying != null) {
                    ControlButton(
                        icon = DesignR.drawable.ic_skip_previous,
                        label = context.getString(R.string.action_previous),
                        action = playerCommand(WidgetPlayerAction.PREVIOUS),
                    )
                }
                ControlButton(
                    icon = if (isPlaying) DesignR.drawable.ic_pause else DesignR.drawable.ic_play,
                    label = context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                    // „Play“ geht über den MediaButtonReceiver, damit die Wiedergabe auch ohne laufenden
                    // Service fortgesetzt wird. Alles andere ignoriert dieser ab API 26 – dafür gibt es
                    // die Verbindung zum laufenden Service.
                    action = if (isPlaying) playerCommand(WidgetPlayerAction.PAUSE) else actionSendBroadcast(playIntent(context)),
                )
                if (nowPlaying != null) {
                    ControlButton(
                        icon = DesignR.drawable.ic_skip_next,
                        label = context.getString(R.string.action_next),
                        action = playerCommand(WidgetPlayerAction.NEXT),
                    )
                }
            }
        }
    }
}

private val COVER_SIZE = 50.dp

@Composable
private fun ControlButton(icon: Int, label: String, action: Action) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = label,
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        // Flache, aber breite Trefferfläche: In einer Zellenhöhe ist kein Platz für 48 dp Höhe.
        modifier = GlanceModifier
            .size(width = 48.dp, height = 20.dp)
            .cornerRadius(10.dp)
            .clickable(action)
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

private fun playerCommand(command: String): Action =
    actionRunCallback<WidgetPlayerAction>(actionParametersOf(WidgetPlayerAction.CommandKey to command))

@OptIn(UnstableApi::class)
private fun playIntent(context: Context): Intent =
    Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
        // Eigene Data-URI, damit sich der PendingIntent von anderen Medientasten-Intents unterscheidet.
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
        }
    }

    companion object {
        val CommandKey = ActionParameters.Key<String>("command")
        const val PAUSE = "pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
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
