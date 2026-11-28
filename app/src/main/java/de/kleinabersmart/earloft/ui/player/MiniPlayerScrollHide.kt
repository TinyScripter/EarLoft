package de.kleinabersmart.earloft.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

/**
 * Moves the mini player out of the way while reading a list (user, 2026-11-21): it slides down as soon as a list on the page
 * scrolls towards its end, and comes back once a list is pushed against its start (the leftover of a drag or fling at the
 * top), on another page ([show]) or when something starts playing. Scrolling back up halfway keeps it hidden.
 *
 * Only listens, never consumes, so it can sit at several levels: above all pages, and additionally directly above lists
 * whose leftover at the top is taken by something in between (pull-to-refresh, a collapsing header) before it would
 * reach the top level ([LocalMiniPlayerScrollHide]). Unlike the swipe-away of [MiniPlayerVisibility] this is not
 * remembered: it is about the current page only. Can be switched off in the settings (`ThemeSettings.hideMiniPlayerOnScroll`);
 * then [hidden] is still tracked but ignored.
 */
@Stable
class MiniPlayerScrollHide : NestedScrollConnection {
    var hidden by mutableStateOf(false)
        private set

    fun show() {
        hidden = false
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        when {
            // Only what a list really scrolled: a list too short to scroll keeps the mini player.
            consumed.y < 0f -> hidden = true
            available.y > 0f -> hidden = false
        }
        return Offset.Zero
    }
}

/**
 * The app's [MiniPlayerScrollHide], for `Modifier.nestedScroll` directly above lists below pull-to-refresh or a collapsing
 * header. Outside the app (previews) an unattached instance that changes nothing.
 */
val LocalMiniPlayerScrollHide = staticCompositionLocalOf { MiniPlayerScrollHide() }

/**
 * Brings the mini player back whenever [page] changes, e.g. another tab of the library pager or another album view: they are no
 * route of their own, and the crate or carousel don't scroll vertically, so it would otherwise stay away there (review5 P54).
 * Not for the first value, which comes with the route change that showed it already.
 */
@Composable
fun ShowMiniPlayerOnPageChange(page: Any?) {
    val scrollHide = LocalMiniPlayerScrollHide.current
    val current by rememberUpdatedState(page)
    LaunchedEffect(scrollHide) {
        snapshotFlow { current }.laterPages().collect { scrollHide.show() }
    }
}

/** Every page after the first one; `null` (not known yet) doesn't count. */
internal fun <T : Any> Flow<T?>.laterPages(): Flow<T> = filterNotNull().distinctUntilChanged().drop(1)
