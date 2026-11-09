package de.kleinabersmart.earloft.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import de.kleinabersmart.earloft.core.designsystem.theme.AlbumColorTheme

/** Ob Mini-Player und Player die Farben des Covers tragen ([ThemeSettings.playerCoverColors]). */
val LocalPlayerCoverColors = staticCompositionLocalOf { true }

/**
 * Farben für Mini-Player und Player: nach dem Cover ([seedColor]) oder, wenn so eingestellt, die App-Farben des
 * umgebenden Themes (dann auch die Systemfarben, falls eingeschaltet).
 */
@Composable
fun PlayerColorTheme(seedColor: Int?, content: @Composable () -> Unit) {
    AlbumColorTheme(seedColor.takeIf { LocalPlayerCoverColors.current }, content = content)
}
