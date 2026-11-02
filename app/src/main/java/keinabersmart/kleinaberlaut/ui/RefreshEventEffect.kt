package keinabersmart.kleinaberlaut.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvent
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvents
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshKind
import keinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

@HiltViewModel
class RefreshEventViewModel @Inject constructor(events: RefreshEvents) : ViewModel() {
    val events: SharedFlow<RefreshEvent> = events.events
}

/** Meldet Start und Ende des Abgleichs von Podcasts und Büchern (mit Zahl der Neuen), solange die App sichtbar ist. */
@Composable
fun RefreshEventEffect(viewModel: RefreshEventViewModel = hiltViewModel()) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val podcasts = event.kind == RefreshKind.PODCASTS
            val text = when (event) {
                is RefreshEvent.Started -> resources.getString(
                    if (podcasts) R.string.refresh_podcasts_started else R.string.refresh_books_started,
                )
                is RefreshEvent.Finished -> when {
                    event.newCount > 0 -> if (podcasts) {
                        resources.getQuantityString(R.plurals.new_content_episodes, event.newCount, event.newCount)
                    } else {
                        resources.getQuantityString(R.plurals.new_content_books, event.newCount, event.newCount)
                    }
                    event.failed -> resources.getString(R.string.refresh_failed)
                    else -> resources.getString(if (podcasts) R.string.refresh_no_new_episodes else R.string.refresh_no_new_books)
                }
            }
            snackbars.show(text)
        }
    }
}
