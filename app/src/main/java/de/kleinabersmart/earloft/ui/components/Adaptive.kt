package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** From this width on, pages like album or playback use two columns (window class "expanded"). */
val TwoColumnMinWidth: Dp = 840.dp

/**
 * Limits settings and forms on wide windows to a readable width ([max]) and centers them,
 * so switches don't sit far away from their text. No effect on the phone and in a detail pane.
 */
fun Modifier.readableWidth(max: Dp = 720.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/**
 * Gives [content] the largest square that fits into the available space (at most [maxSide]) and centers it. A cover with
 * `aspectRatio` alone grows beyond the space when height is insufficient and then overlaps its neighbors.
 */
@Composable
fun FitSquare(modifier: Modifier = Modifier, maxSide: Dp = Dp.Infinity, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        content(Modifier.size(minOf(maxWidth, maxHeight, maxSide)))
    }
}
