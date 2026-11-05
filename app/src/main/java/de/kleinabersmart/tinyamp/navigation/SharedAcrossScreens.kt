package de.kleinabersmart.tinyamp.navigation

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** Der [SharedTransitionScope] um die `NavDisplay`; `null` außerhalb (Vorschau, Tests). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Teilt [content] mit dem Element gleichen [key]s auf der anderen Seite: Beim Seitenwechsel bleibt es stehen (oder
 * gleitet an seine neue Stelle), statt mit der Seite aus- und wieder einzublenden. Außerhalb der `NavDisplay` ein
 * schlichter Rahmen.
 */
@Composable
fun SharedAcrossScreens(key: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shared = LocalSharedTransitionScope.current
    val sharedModifier = if (shared == null) {
        Modifier
    } else {
        val animated = LocalNavAnimatedContentScope.current
        with(shared) { Modifier.sharedElement(rememberSharedContentState(key), animated) }
    }
    Box(modifier.then(sharedModifier)) { content() }
}
