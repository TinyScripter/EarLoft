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

/** Ab dieser Breite stehen Seiten wie Album oder Wiedergabe zweispaltig (Fensterklasse „expanded“). */
val TwoColumnMinWidth: Dp = 840.dp

/**
 * Begrenzt Einstellungen und Formulare auf breiten Fenstern auf eine lesbare Breite ([max]) und zentriert sie,
 * damit Schalter nicht weit weg von ihrem Text stehen. Auf dem Handy und in einer Detailspalte ohne Wirkung.
 */
fun Modifier.readableWidth(max: Dp = 720.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/**
 * Gibt [content] das größte Quadrat, das in den freien Platz passt (höchstens [maxSide]), und zentriert es. Ein Cover mit
 * `aspectRatio` allein wächst bei zu wenig Höhe über den Platz hinaus und liegt dann über den Nachbarn.
 */
@Composable
fun FitSquare(modifier: Modifier = Modifier, maxSide: Dp = Dp.Infinity, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        content(Modifier.size(minOf(maxWidth, maxHeight, maxSide)))
    }
}
