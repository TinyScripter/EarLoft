package keinabersmart.kleinaberlaut.ui.spoken

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import keinabersmart.kleinaberlaut.core.model.BookKind
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.ui.components.TwoColumnMinWidth
import keinabersmart.kleinaberlaut.core.data.spoken.BookDownload
import keinabersmart.kleinaberlaut.core.download.BookDownloadProgress
import keinabersmart.kleinaberlaut.core.download.BookDownloads
import keinabersmart.kleinaberlaut.core.download.BookTransfer
import keinabersmart.kleinaberlaut.core.download.DownloadStart
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.ui.formatBytes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@HiltViewModel(assistedFactory = BookViewModel.Factory::class)
class BookViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    books: BookRepository,
    private val launcher: BookLauncher,
    private val downloads: BookDownloads,
    downloadProgress: BookDownloadProgress,
) : ViewModel() {

    val book: StateFlow<BookWithProgress?> = books.book(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val download: StateFlow<BookDownload> = books.download(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDownload(TrackDownload.NONE, 0))

    /** Bytes des ladenden Buchs; `null`, solange es nicht lädt. */
    val transfer: StateFlow<BookTransfer?> = downloadProgress.transfers.map { it[bookId] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun startDownload() {
        viewModelScope.launch {
            when (downloads.download(bookId)) {
                DownloadStart.OFFLINE -> _messages.emit(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI -> _messages.emit(R.string.podcast_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }

    /** Bricht einen laufenden Download ab oder löscht die geladenen Dateien. */
    fun removeDownload() {
        viewModelScope.launch { downloads.remove(bookId) }
    }

    val chapters: StateFlow<List<BookChapter>> = books.chapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play() = launcher.play(bookId)

    fun playFrom(positionMs: Long) = launcher.play(bookId, positionMs)

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): BookViewModel
    }
}

/** Buchseite: Beschreibung, Fortschritt, Abspielen und Kapitelliste (Konzept 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Long,
    onBack: () -> Unit,
    isCurrent: Boolean,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookViewModel = hiltViewModel<BookViewModel, BookViewModel.Factory> { it.create(bookId) },
) {
    val item by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val book = item?.book
    val progress = item?.progress
    val download by viewModel.download.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.book_download_delete_title)) },
            text = { Text(stringResource(R.string.book_download_delete_message, formatBytes(download.bytes))) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDownload()
                    confirmDelete = false
                }) { Text(stringResource(R.string.book_download_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(book?.title.orEmpty(), maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        if (book == null) return@Column
        val header = @Composable {
            BookHeader(
                book, progress,
                download = download.state,
                transfer = transfer,
                onPlay = if (isCurrent) onOpenPlayer else viewModel::play,
                onDownload = viewModel::startDownload,
                onCancelDownload = viewModel::removeDownload,
                onDeleteDownload = { confirmDelete = true },
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= TwoColumnMinWidth) {
                // Breites Fenster: Cover und Abspielen links, Beschreibung und Kapitel rechts daneben.
                val headerWidth = maxWidth * 0.4f
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(headerWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) { header() }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                        bookDetailItems(book, chapters, onChapter = viewModel::playFrom)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") { header() }
                    bookDetailItems(book, chapters, onChapter = viewModel::playFrom)
                }
            }
        }
    }
}

/** Cover, Titel, Autor, Dauer, Fortschritt und Abspielen. */
@Composable
private fun BookHeader(
    book: Book,
    progress: BookProgress?,
    download: TrackDownload,
    transfer: BookTransfer?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookArt(book.id, Modifier.widthIn(max = 280.dp).fillMaxWidth())
        Text(
            book.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (book.narrator.isNotBlank()) {
            Text(book.narrator, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Podcast-Feeds nennen nicht immer eine Dauer.
        if (book.durationMs > 0) Text(formatDuration(book.durationMs), style = MaterialTheme.typography.bodyMedium)
        if (progress != null && progress.positionMs > 0) {
            LinearProgressIndicator(
                progress = { if (progress.finished) 1f else progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                drawStopIndicator = {},
            )
            Text(
                if (progress.finished) {
                    stringResource(R.string.spoken_finished)
                } else {
                    stringResource(R.string.spoken_percent_listened, (progress.fraction * 100).toInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Abspielen und Download sind gleich groß; Podcast-Folgen haben hier keinen Download (eigener Knopf je Folge).
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPlay, modifier = Modifier.weight(1f)) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null)
                val label = when {
                    progress?.finished == true -> R.string.spoken_play_again
                    progress != null && progress.positionMs > 0 -> R.string.spoken_resume
                    else -> R.string.spoken_play
                }
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            if (book.kind == BookKind.BOOK) {
                DownloadButton(download, onDownload, onCancelDownload, onDeleteDownload, Modifier.weight(1f))
            }
        }
        if (book.kind == BookKind.BOOK && (download == TrackDownload.QUEUED || download == TrackDownload.RUNNING)) {
            Text(
                transferText(transfer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Herunterladen; beim Laden Abbrechen, danach Löschen (mit Rückfrage), nach einem Fehler erneut versuchen. */
@Composable
private fun DownloadButton(
    state: TrackDownload,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, label, onClick) = when (state) {
        TrackDownload.QUEUED, TrackDownload.RUNNING -> Triple(DesignR.drawable.ic_close, R.string.action_cancel, onCancel)
        TrackDownload.DONE -> Triple(DesignR.drawable.ic_delete, R.string.book_download_delete, onDelete)
        TrackDownload.FAILED -> Triple(DesignR.drawable.ic_error, R.string.book_download_retry, onDownload)
        TrackDownload.NONE, TrackDownload.REMOVED -> Triple(DesignR.drawable.ic_download, R.string.book_download, onDownload)
    }
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

/** „32 MB von 500 MB geladen“; ohne bekannte Gesamtgröße nur der übertragene Teil, vor dem Start „Wartet“. */
@Composable
private fun transferText(transfer: BookTransfer?): String = when {
    transfer == null -> stringResource(R.string.book_download_waiting)
    transfer.totalBytes == null -> stringResource(R.string.book_download_progress_unknown, formatBytes(transfer.doneBytes))
    else -> stringResource(R.string.book_download_progress, formatBytes(transfer.doneBytes), formatBytes(transfer.totalBytes!!))
}

/** Beschreibung und Kapitelliste; ein Tipp auf ein Kapitel spielt ab dessen Anfang. */
private fun LazyListScope.bookDetailItems(book: Book, chapters: List<BookChapter>, onChapter: (Long) -> Unit) {
    if (book.description.isNotBlank()) {
        item(key = "description") {
            Text(
                book.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
    }
    if (chapters.isNotEmpty()) {
        item(key = "chapters-header") {
            Text(
                stringResource(R.string.spoken_chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(chapters, key = { _, chapter -> chapter.index }) { _, chapter ->
            ListItem(
                headlineContent = { Text(chapterTitle(chapter)) },
                trailingContent = { Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.clickable { onChapter(chapter.startMs) },
            )
        }
    }
}

@Composable
internal fun chapterTitle(chapter: BookChapter): String =
    chapter.title.ifBlank { stringResource(R.string.spoken_chapter_n, chapter.index + 1) }
