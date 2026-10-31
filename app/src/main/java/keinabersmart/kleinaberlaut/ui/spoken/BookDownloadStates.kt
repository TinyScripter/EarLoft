package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.BookDownload
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@HiltViewModel
class BookDownloadStatesViewModel @Inject constructor(books: BookRepository) : ViewModel() {
    val states: StateFlow<Map<Long, BookDownload>> = books.downloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** Download-Stand der Bücher für alle Kacheln und Listen; ohne Eintrag gilt [TrackDownload.NONE]. */
val LocalBookDownloads: ProvidableCompositionLocal<Map<Long, BookDownload>> = compositionLocalOf { emptyMap() }

@Composable
fun rememberBookDownloads(viewModel: BookDownloadStatesViewModel = hiltViewModel()): Map<Long, BookDownload> {
    val states by viewModel.states.collectAsStateWithLifecycle()
    return states
}

/** Kleines Symbol oben links auf dem Cover: geladen (Haken) oder wartet/lädt (Pfeil). Sonst nichts. */
@Composable
fun BookDownloadBadge(bookId: Long, modifier: Modifier = Modifier) {
    val (icon, description) = when (LocalBookDownloads.current[bookId]?.state) {
        TrackDownload.DONE -> DesignR.drawable.ic_download_done to R.string.book_download_done
        TrackDownload.QUEUED, TrackDownload.RUNNING -> DesignR.drawable.ic_download to R.string.book_download_running
        else -> return
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        modifier = modifier,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.padding(3.dp).size(16.dp),
        )
    }
}
