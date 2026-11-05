package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteBookDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlayRequest
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.sync.PhoneRemote
import keinabersmart.kleinaberlaut.wear.sync.PhoneSelections
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Eine Folge bzw. ein Buch des Handys (Wear-Konzept 17): am Handy abspielen oder mit „Auf der Uhr“ herunterladen. Ob es auf
 * der Uhr ist, erkennt sie an Server bzw. Abo und Kennung ([WearRemoteBookDto.serverId], [WearRemoteBookDto.remoteId]):
 * Das Folgen-Manifest legt genau die gewählten an.
 */
@HiltViewModel(assistedFactory = RemoteSpokenViewModel.Factory::class)
class RemoteSpokenViewModel @AssistedInject constructor(
    @Assisted private val item: WearRemoteBookDto,
    private val remote: PhoneRemote,
    private val phoneSelections: PhoneSelections,
    books: BookRepository,
    podcasts: PodcastRepository,
) : ViewModel() {

    /** Gewünschter Stand nach dem Umschalten, bis das Folgen-Manifest ihn bestätigt (oder [PENDING_TIMEOUT_MS] vergeht). */
    private val pending = MutableStateFlow<Boolean?>(null)
    private val failed = MutableStateFlow(false)
    private var pendingTimeout: Job? = null

    val onWatch: StateFlow<OnWatchState> = combine(
        books.observeIdByRemote(item.serverId, item.remoteId),
        podcasts.downloadedIds(),
        books.downloads(),
        pending,
        failed,
    ) { localId, downloadedEpisodes, bookDownloads, wanted, failedNow ->
        val confirmed = localId != null
        if (wanted == confirmed) pending.value = null
        val done = localId != null && (localId in downloadedEpisodes || bookDownloads[localId]?.state == TrackDownload.DONE)
        OnWatchState(checked = wanted ?: confirmed, loading = confirmed && !done, failed = failedNow)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnWatchState(checked = false))

    /** Das Handy spielt das Buch bzw. die Folge an der gemerkten Stelle (wie in der App). @return `false` ohne Handy. */
    suspend fun play(): Boolean = remote.play(WearRemotePlayRequest(WearRemoteListKind.CONTINUE, refId = item.id))

    fun setOnWatch(selected: Boolean) {
        viewModelScope.launch {
            failed.value = false
            pending.value = selected
            if (!phoneSelections.setSpokenSelected(item.id, selected)) {
                pending.value = null
                failed.value = true
                return@launch
            }
            pendingTimeout?.cancel()
            pendingTimeout = launch {
                delay(PENDING_TIMEOUT_MS)
                pending.value = null
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(item: WearRemoteBookDto): RemoteSpokenViewModel
    }

    private companion object {
        /** Das Handy schickt das Folgen-Manifest erst nach 2 s Ruhe; die Uhr legt danach an. */
        const val PENDING_TIMEOUT_MS = 20_000L
    }
}

/** [onPlayed] wechselt nach dem Start zur Wiedergabe-Ansicht des Handys. */
@Composable
fun RemoteSpokenScreen(
    item: WearRemoteBookDto,
    onPlayed: () -> Unit,
    viewModel: RemoteSpokenViewModel = hiltViewModel<RemoteSpokenViewModel, RemoteSpokenViewModel.Factory> { it.create(item) },
) {
    val onWatch by viewModel.onWatch.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RemoteSpokenContent(
        item = item,
        onWatch = onWatch,
        onPlay = { scope.launch { if (viewModel.play()) onPlayed() } },
        onToggleWatch = viewModel::setOnWatch,
    )
}

@Composable
private fun RemoteSpokenContent(item: WearRemoteBookDto, onWatch: OnWatchState, onPlay: () -> Unit, onToggleWatch: (Boolean) -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(item.title, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.author.isNotBlank()) {
                item {
                    Text(
                        item.author,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text(stringResource(R.string.remote_play_on_phone)) },
                )
            }
            onWatchSwitch(onWatch, onToggleWatch, transformationSpec)
        }
    }
}

@WearPreviewDevices
@Composable
private fun RemoteSpokenPreview() {
    MaterialTheme {
        RemoteSpokenContent(
            item = WearRemoteBookDto(1, "#383 Drohnen überall – wie eine Waffe den Krieg verändert", "Was tun, Herr General?", "f1", "g1"),
            onWatch = OnWatchState(checked = true, loading = true),
            onPlay = {},
            onToggleWatch = {},
        )
    }
}
