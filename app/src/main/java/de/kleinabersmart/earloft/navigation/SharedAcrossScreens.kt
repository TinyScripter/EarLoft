package de.kleinabersmart.earloft.navigation

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** The [SharedTransitionScope] around the `NavDisplay`; `null` outside of it (preview, tests). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Shares [content] with the element with the same [key] on the other page: during a page transition it stays in place (or
 * glides to its new position) instead of fading out and back in with the page. Outside the `NavDisplay` a
 * plain wrapper.
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
