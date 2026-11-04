package keinabersmart.kleinaberlaut.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/**
 * Lautstärke des Cast-Geräts, kurz eingeblendet nach jedem Druck auf eine Lautstärketaste ([presses] zählt hoch).
 * Beim Casten fängt die `MainActivity` die Tasten ab, weil die System-Oberfläche des Pixels ihren Dialog für
 * Cast-Sitzungen nur sporadisch zeigt (am Pixel 10 Pro gesehen, 2026-11-04; der Emulator zeigt ihn immer).
 * [state] wird nur gelesen, solange die Anzeige steht.
 */
@Composable
fun CastVolumeIndicator(presses: Int, state: StateFlow<PlayerState>, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        visible = true
        delay(VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        val player by state.collectAsStateWithLifecycle()
        CastVolumeCard(
            deviceName = rememberCastDeviceName(player.isRemote),
            volume = player.deviceVolume,
            maxVolume = player.maxDeviceVolume,
        )
    }
}

@Composable
private fun CastVolumeCard(deviceName: String?, volume: Int, maxVolume: Int, modifier: Modifier = Modifier) {
    val fraction = if (maxVolume > 0) (volume.toFloat() / maxVolume).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(fraction, label = "castVolume")
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Icon(painterResource(DesignR.drawable.ic_volume_up), contentDescription = stringResource(R.string.cast_volume))
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    deviceName ?: stringResource(R.string.cast_volume),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    drawStopIndicator = {},
                )
            }
            Text(stringResource(R.string.cast_volume_percent, (fraction * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Wie der Lautstärkebalken der Uhr-App: so lange nach dem letzten Tastendruck. */
private const val VISIBLE_MS = 1_500L
private val CARD_MAX_WIDTH = 360.dp

@Preview
@Composable
private fun CastVolumeCardPreview() {
    KleinaberlautTheme(darkTheme = true) {
        CastVolumeCard(deviceName = "Büro", volume = 4, maxVolume = 20)
    }
}
