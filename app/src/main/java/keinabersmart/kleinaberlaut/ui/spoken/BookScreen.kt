package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.material3.SnackbarDuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.ui.components.GroupMargin
import keinabersmart.kleinaberlaut.ui.components.TwoColumnMinWidth
import keinabersmart.kleinaberlaut.ui.components.WatchTarget
import keinabersmart.kleinaberlaut.ui.components.WatchToggleButton
import keinabersmart.kleinaberlaut.ui.components.groupedItems
import keinabersmart.kleinaberlaut.core.download.BookTransfer
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloadProgress
import keinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.ui.formatBytes
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Buchseite: Beschreibung, Fortschritt, Abspielen und Kapitelliste (Konzept 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Long,
    onBack: () -> Unit,
    isCurrent: Boolean,
    onOpenPlayer: () -> Unit,
    onOpenFeed: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookViewModel = hiltViewModel<BookViewModel, BookViewModel.Factory> { it.create(bookId) },
) {
    val item by viewModel.book.collectAsStateWithLifecycle()
    val trialFeedId by viewModel.trialFeedId.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val book = item?.book
    val progress = item?.progress
    val download by viewModel.download.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val episodeFraction by viewModel.episodeFraction.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
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
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                }
            },
            // Folgen einzeln auf die Uhr (Wear-Konzept 16.1); Bücher folgen später.
            actions = { if (book?.kind == BookKind.EPISODE) WatchToggleButton(WatchTarget.Episode(book.id)) },
        )
        if (book == null) return@Column
        val header = @Composable {
            BookHeader(
                book, progress,
                download = download.state,
                transfer = transfer,
                episodeFraction = episodeFraction,
                onPlay = if (isCurrent) onOpenPlayer else viewModel::play,
                onDownload = viewModel::startDownload,
                onCancelDownload = viewModel::removeDownload,
                // Eine Folge ist klein und wird wie in der Folgenliste ohne Rückfrage gelöscht.
                onDeleteDownload = { if (book.kind == BookKind.EPISODE) viewModel.removeDownload() else confirmDelete = true },
                trialNotice = trialFeedId?.let { feedId ->
                    @Composable {
                        TrialNotice(
                            text = stringResource(R.string.book_trial_notice),
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = { onOpenFeed(feedId) },
                        )
                    }
                },
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
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        bookDetailItems(book, chapters, onChapter = viewModel::playFrom)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
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
    episodeFraction: Float?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    trialNotice: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Niedrig (kleines Handy, quer): kleineres Cover, damit „Fortsetzen“ ohne Scrollen sichtbar bleibt.
        val screenHeight = LocalConfiguration.current.screenHeightDp
        val coverMax = if (screenHeight < 700) (screenHeight * 0.2f).coerceIn(96f, 280f).dp else 280.dp
        BookArt(book.id, Modifier.widthIn(max = coverMax).fillMaxWidth())
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
        // Folge eines nicht abonnierten Podcasts (Konzept 12.10): direkt über dem Abspielen, Tipp führt zum Podcast.
        trialNotice?.invoke()
        // Abspielen nimmt zwei Drittel der Zeile, Download ein Drittel (Bücher und Folgen); Bücher vom Gerät haben keinen Download.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPlay, modifier = Modifier.weight(if (book.isLocal) 1f else 2f)) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null)
                val label = when {
                    progress?.finished == true -> R.string.spoken_play_again
                    progress != null && progress.positionMs > 0 -> R.string.spoken_resume
                    else -> R.string.spoken_play
                }
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            if (!book.isLocal) DownloadButton(download, onDownload, onCancelDownload, onDeleteDownload, Modifier.weight(1f))
        }
        if (download == TrackDownload.QUEUED || download == TrackDownload.RUNNING) {
            Text(
                if (book.kind == BookKind.EPISODE) episodeTransferText(episodeFraction) else transferText(transfer),
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
    // Schmaler Knopf: kleinere Ränder, damit „Download“ und „Abbrechen“ auch bei großer Schrift Platz haben.
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(painterResource(icon), contentDescription = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** „32 MB von 500 MB geladen“; ohne bekannte Gesamtgröße nur der übertragene Teil, vor dem Start „Wartet“. */
@Composable
internal fun transferText(transfer: BookTransfer?): String = when {
    transfer == null -> stringResource(R.string.book_download_waiting)
    transfer.totalBytes == null -> stringResource(R.string.book_download_progress_unknown, formatBytes(transfer.doneBytes))
    else -> stringResource(R.string.book_download_progress, formatBytes(transfer.doneBytes), formatBytes(transfer.totalBytes!!))
}

/** „45 % geladen“; vor dem Start und ohne bekannte Dateigröße „Wartet…“. */
@Composable
private fun episodeTransferText(fraction: Float?): String =
    if (fraction == null || fraction == EpisodeDownloadProgress.UNKNOWN) {
        stringResource(R.string.book_download_waiting)
    } else {
        stringResource(R.string.episode_download_progress, (fraction * 100).toInt())
    }

/** Beschreibung und Kapitelliste; ein Tipp auf ein Kapitel spielt ab dessen Anfang. */
private fun LazyListScope.bookDetailItems(book: Book, chapters: List<BookChapter>, onChapter: (Long) -> Unit) {
    if (book.description.isNotBlank()) {
        item(key = "description") {
            Text(
                rememberDescription(book.description),
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
                modifier = Modifier.padding(start = GroupMargin + 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        groupedItems(chapters, key = { it.index }) { chapter ->
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
