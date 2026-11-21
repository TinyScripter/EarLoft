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
 * From this height for header and list on, a chip bar stays pinned above the list; below it (small phone in landscape) it scrolls along.
 * Low, because the chips are only one or two lines high and area and tabs already sit above the library lists (Pixel 10 Pro in
 * portrait: about 475 dp).
 */
private val PINNED_CHIPS_MIN_HEIGHT = 320.dp

/** Inner padding of the library grids; if the header is pinned above them ([pinnedHeader]), only the row spacing at the top. */
fun gridContentPadding(pinnedHeader: Boolean): PaddingValues =
    PaddingValues(start = 12.dp, top = if (pinnedHeader) 8.dp else 12.dp, end = 12.dp, bottom = 12.dp)

/**
 * Frame for a list with a header (chip bar): with [pin] and enough height, the header stays pinned above the list; otherwise [content] gets it back as `inlineHeader` and draws it itself as the first entry. [headerPadding]
 * replaces the inner padding the header would have in the list.
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
