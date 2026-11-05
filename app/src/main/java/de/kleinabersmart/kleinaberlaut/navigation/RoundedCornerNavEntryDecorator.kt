package de.kleinabersmart.kleinaberlaut.navigation

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope

private val FallbackCornerRadius = 28.dp

/**
 * Rundet die Ecken eines Screens ab, solange er gerade ein- oder ausgeblendet wird (z. B. bei der Zurück-Geste),
 * mit dem Radius der echten Display-Ecken. Im Ruhezustand bleibt der Screen eckig.
 */
@Composable
fun rememberRoundedCornerNavEntryDecorator(): NavEntryDecorator<NavKey> {
    val deviceRadius = rememberDisplayCornerRadius()
    return remember(deviceRadius) {
        NavEntryDecorator { entry ->
            val transition = LocalNavAnimatedContentScope.current.transition
            val radius by transition.animateDp(
                transitionSpec = { tween(100) },
                label = "screenCornerRadius",
            ) { state -> if (state == EnterExitState.Visible) 0.dp else deviceRadius }
            // Deckender Hintergrund, sonst scheint bei der Zurück-Geste der Screen dahinter durch.
            Box(Modifier.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.background)) {
                entry.Content()
            }
        }
    }
}

@Composable
private fun rememberDisplayCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val px = view.rootWindowInsets
                ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                ?.radius
            if (px != null && px > 0) with(density) { px.toDp() } else FallbackCornerRadius
        } else {
            FallbackCornerRadius
        }
    }
}
