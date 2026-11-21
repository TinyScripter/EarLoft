package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Tapping the tab that is already selected while its start page is on top: the lists of that start page scroll back to
 * their beginning (the usual bottom bar behaviour). The app provides the requests per tab via [LocalScrollToTop].
 */
class ScrollToTopRequests {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val events: Flow<Unit> get() = requests

    fun request() {
        requests.tryEmit(Unit)
    }
}

/** The scroll-to-top requests of the tab the composable sits in; outside a tab's start page nothing ever arrives. */
val LocalScrollToTop = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

@Composable
fun ScrollToTopEffect(state: LazyListState) {
    val events = LocalScrollToTop.current
    LaunchedEffect(events, state) { events.collect { state.animateScrollToItem(0) } }
}

@Composable
fun ScrollToTopEffect(state: LazyGridState) {
    val events = LocalScrollToTop.current
    LaunchedEffect(events, state) { events.collect { state.animateScrollToItem(0) } }
}

@Composable
fun ScrollToTopEffect(state: ScrollState) {
    val events = LocalScrollToTop.current
    LaunchedEffect(events, state) { events.collect { state.animateScrollTo(0) } }
}
