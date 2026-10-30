package keinabersmart.kleinaberlaut.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ab dieser Breite stehen Seiten wie Album oder Wiedergabe zweispaltig (Fensterklasse „expanded“). */
val TwoColumnMinWidth: Dp = 840.dp

/**
 * Begrenzt Einstellungen und Formulare auf breiten Fenstern auf eine lesbare Breite und zentriert sie,
 * damit Schalter nicht weit weg von ihrem Text stehen. Auf dem Handy und in einer Detailspalte ohne Wirkung.
 */
fun Modifier.readableWidth(): Modifier = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)
