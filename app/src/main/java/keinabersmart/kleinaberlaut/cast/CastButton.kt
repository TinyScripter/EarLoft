package keinabersmart.kleinaberlaut.cast

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.cast.Cast
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi

/** Cast-Knopf; erscheint erst, wenn Cast bereit ist (ohne Play-Dienste nie). */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    MediaRouteButton(modifier)
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
