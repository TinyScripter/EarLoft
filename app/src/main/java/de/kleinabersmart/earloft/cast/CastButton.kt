package de.kleinabersmart.earloft.cast

import androidx.annotation.OptIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.cast.Cast
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi

/**
 * Cast-Knopf; erscheint erst, wenn Cast bereit ist (ohne Play-Dienste nie). [active]: Es wird gerade auf ein Cast-Gerät
 * gespielt – dann in der Akzentfarbe wie die übrigen eingeschalteten Knöpfe der Wiedergabe-Ansicht.
 */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(LocalContentColor provides color) {
        MediaRouteButton(modifier)
    }
}

/** Name des Cast-Geräts, solange dorthin gespielt wird. */
@OptIn(UnstableApi::class)
@Composable
fun rememberCastDeviceName(isRemote: Boolean): String? {
    val context = LocalContext.current
    return remember(isRemote) {
        if (isRemote) Cast.getSingletonInstance(context).currentCastSession?.castDevice?.friendlyName else null
    }
}
