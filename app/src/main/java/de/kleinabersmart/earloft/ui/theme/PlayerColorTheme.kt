package de.kleinabersmart.earloft.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import de.kleinabersmart.earloft.core.designsystem.theme.AlbumColorTheme

/** Whether mini player and player use the cover's colors ([ThemeSettings.playerCoverColors]). */
val LocalPlayerCoverColors = staticCompositionLocalOf { true }

/**
 * Colors for mini player and player: based on the cover ([seedColor]) or, if configured so, the app colors of the
 * surrounding theme (then also the system colors, if enabled).
 */
@Composable
fun PlayerColorTheme(seedColor: Int?, content: @Composable () -> Unit) {
    AlbumColorTheme(seedColor.takeIf { LocalPlayerCoverColors.current }, content = content)
}
