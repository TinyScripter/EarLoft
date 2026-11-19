package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Ab dieser Höhe für Kopf und Liste steht eine Chip-Leiste fest über der Liste; darunter (kleines Handy quer) scrollt sie mit.
 * Kleiner als bei der Podcast-Seite (560 dp), denn die Chips sind nur eine bis zwei Zeilen hoch und über den Bibliothekslisten
 * stehen schon Bereich und Reiter (Pixel 10 Pro hochkant: rund 475 dp).
 */
private val PINNED_CHIPS_MIN_HEIGHT = 320.dp

/** Innenabstand der Bibliotheksraster; steht der Kopf fest darüber ([pinnedHeader]), oben nur der Zeilenabstand. */
fun gridContentPadding(pinnedHeader: Boolean): PaddingValues =
    PaddingValues(start = 12.dp, top = if (pinnedHeader) 8.dp else 12.dp, end = 12.dp, bottom = 12.dp)

/**
 * Rahmen für eine Liste mit Kopf (Chip-Leiste): Mit [pin] und genug Höhe steht der Kopf fest über der Liste wie auf der
 * Podcast-Seite, sonst gibt [content] ihn als `inlineHeader` zurück und zeichnet ihn als ersten Eintrag selbst. [headerPadding]
 * ersetzt den Innenabstand, den der Kopf in der Liste hätte.
 */
@Composable
fun PinnableHeaderBox(
    header: (@Composable () -> Unit)?,
    pin: Boolean,
    modifier: Modifier = Modifier,
    headerPadding: PaddingValues = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp),
    content: @Composable BoxScope.(inlineHeader: (@Composable () -> Unit)?) -> Unit,
) {
    if (header == null || !pin) {
        Box(modifier) { content(header) }
        return
    }
    BoxWithConstraints(modifier) {
        val pinned = maxHeight >= PINNED_CHIPS_MIN_HEIGHT
        Column(Modifier.fillMaxSize()) {
            if (pinned) Box(Modifier.fillMaxWidth().padding(headerPadding)) { header() }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(if (pinned) null else header) }
        }
    }
}
