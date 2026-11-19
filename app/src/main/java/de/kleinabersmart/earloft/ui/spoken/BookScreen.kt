package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.material3.SnackbarDuration
import androidx.compose.ui.graphics.Color
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
import de.kleinabersmart.earloft.core.model.BookKind
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.SeriesRef
import de.kleinabersmart.earloft.core.model.parseSeries
import de.kleinabersmart.earloft.core.model.bookAuthors
import de.kleinabersmart.earloft.core.model.parsePeople
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.TextStyle
import de.kleinabersmart.earloft.navigation.BookGroupKind
import androidx.compose.ui.text.style.TextAlign
import java.text.NumberFormat
import java.util.Locale
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.EpisodePerson
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.ui.components.ShareButton
import de.kleinabersmart.earloft.ui.components.WatchTarget
import de.kleinabersmart.earloft.ui.components.WatchToggleButton
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.core.download.BookTransfer
import de.kleinabersmart.earloft.core.download.EpisodeDownloadProgress
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.formatBytes
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import coil3.compose.AsyncImage

/** Book page: description, progress, play and chapter list (concept 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenFeed: (String) -> Unit,
    onOpenGroup: (BookGroupKind, String) -> Unit,
    onReplaceBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookViewModel = hiltViewModel<BookViewModel, BookViewModel.Factory> { it.create(bookId) },
    upload: ArdUploadViewModel = hiltViewModel<ArdUploadViewModel, ArdUploadViewModel.Factory> { it.create(bookId) },
) {
    val uploadState by upload.state.collectAsStateWithLifecycle()
    val canUpload by upload.hasServer.collectAsStateWithLifecycle()
    val item by viewModel.book.collectAsStateWithLifecycle()
    val trialFeedId by viewModel.trialFeedId.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    val bookmarksViewModel: BookmarksViewModel = hiltViewModel()
    val bookmarks by remember(bookId) { bookmarksViewModel.of(bookId) }.collectAsStateWithLifecycle(emptyList())
    val bookmarkActions = rememberBookmarkActions(bookmarksViewModel)
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
    LaunchedEffect(viewModel) { viewModel.openPlayer.collect { onOpenPlayer() } }
    LaunchedEffect(upload) {
        upload.uploaded.collect {
            snackbars.show(resources.getString(R.string.ard_upload_done), SnackbarDuration.Long)
            viewModel.markLeaving()
            onReplaceBook(it.bookId)
        }
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
            // Episodes and books individually to the watch (Wear concept 16.1/16.2); ⋮ with played/unplayed, queue etc.
            // Episodes first with the star ("Starred").
            actions = {
                item?.let { EpisodeStarButton(it) }
                if (book != null) {
                    if (book.kind == BookKind.BOOK || !book.isLocal) ShareButton(ShareTarget.Book(book.id))
                    WatchToggleButton(WatchTarget.Spoken(book.id))
                }
                item?.let { EpisodeMenuButton(it) }
            },
        )
        if (book == null) {
            // Removed from the library (ARD Sounds): close the page.
            LaunchedEffect(Unit) { if (viewModel.wasLoaded) onBack() }
            return@Column
        }
        val header = @Composable {
            BookHeader(
                book, progress,
                download = download.state,
                partial = download.takeIf { it.partial }?.let { it.doneFiles to it.totalFiles },
                transfer = transfer,
                episodeFraction = episodeFraction,
                onPlay = viewModel::play,
                onDownload = viewModel::startDownload,
                onCancelDownload = viewModel::removeDownload,
                // An episode is small and, as in the episode list, is deleted without confirmation.
                onDeleteDownload = { if (book.kind == BookKind.EPISODE) viewModel.removeDownload() else confirmDelete = true },
                onOpenGroup = onOpenGroup,
                onUpload = upload::start.takeIf { book.isArdSounds && canUpload },
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
                // Wide window: cover and play on the left, description and chapters on the right next to it.
                val headerWidth = maxWidth * 0.4f
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(headerWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) { header() }
                    FastScrollLazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        bookDetailItems(book, chapters, persons, onChapter = viewModel::playFrom)
                        bookmarkItems(bookmarks, bookmarkActions, onJump = viewModel::playFrom)
                    }
                }
            } else {
                FastScrollLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") { header() }
                    bookDetailItems(book, chapters, persons, onChapter = viewModel::playFrom)
                    bookmarkItems(bookmarks, bookmarkActions, onJump = viewModel::playFrom)
                }
            }
        }
        bookmarkActions.Dialog()
    }
    ArdUploadDialogs(uploadState, title = book?.title.orEmpty(), onConfirm = upload::confirm, onDismiss = upload::dismiss)
}

/** Cover, title, author, duration, progress and play. */
@Composable
private fun BookHeader(
    book: Book,
    progress: BookProgress?,
    download: TrackDownload,
    /** Partially downloaded: finished and all files. */
    partial: Pair<Int, Int>?,
    transfer: BookTransfer?,
    episodeFraction: Float?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onOpenGroup: (BookGroupKind, String) -> Unit,
    /** Books from ARD Sounds: "Upload to Audiobookshelf" (`null` = don't offer). */
    onUpload: (() -> Unit)? = null,
    trialNotice: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Low (small phone, landscape): smaller cover, so "Resume" stays visible without scrolling.
        val screenHeight = LocalConfiguration.current.screenHeightDp
        val coverMax = if (screenHeight < 700) (screenHeight * 0.2f).coerceIn(96f, 280f).dp else 280.dp
        BookArt(book.id, Modifier.widthIn(max = coverMax).fillMaxWidth())
        Text(
            book.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        // Many subtitles only name the series and volume ("Carl Mørck 10"), which appear below anyway, or are already in the title.
        book.subtitle?.takeUnless { isSeriesOnly(it, book.series) || book.title.contains(it, ignoreCase = true) }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        // For books, authors, series and narrators each lead to their page (Library → Authors or Series);
        // episodes name the podcast.
        val isBook = book.kind == BookKind.BOOK
        if (isBook) {
            // Authors first, translators etc. afterwards and without their own page.
            val authors = bookAuthors(book.author)
            PeopleLine(
                names = authors + parsePeople(book.author).filterNot { it in authors },
                opens = { it in authors },
                clickLabel = stringResource(R.string.book_open_author),
                style = MaterialTheme.typography.bodyLarge,
                onClick = { onOpenGroup(BookGroupKind.AUTHOR, it) },
            )
        } else {
            Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isBook) {
            parseSeries(book.series).forEach { series ->
                Text(
                    seriesLabel(series),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.book_open_series)) {
                        onOpenGroup(BookGroupKind.SERIES, series.name)
                    },
                )
            }
        }
        if (book.narrator.isNotBlank()) {
            PeopleLine(
                names = parsePeople(book.narrator),
                opens = { isBook },
                clickLabel = stringResource(R.string.book_open_narrator),
                style = MaterialTheme.typography.bodyMedium,
                onClick = { onOpenGroup(BookGroupKind.NARRATOR, it) },
            )
        }
        // Genres from Audiobookshelf, each leads to the books of that genre.
        if (isBook && book.genres.isNotEmpty()) {
            PeopleLine(
                names = book.genres,
                opens = { true },
                clickLabel = stringResource(R.string.book_open_genre),
                style = MaterialTheme.typography.bodyMedium,
                onClick = { onOpenGroup(BookGroupKind.GENRE, it) },
            )
        }
        // Episodes: publication with time of day (news come several times a day under the same title). Podcast feeds don't
        // always give a duration.
        // Books: year, publisher, language and duration; for started ones the remaining time (content time, without speed).
        val remaining = progress?.takeIf { isBook && !it.finished && it.positionMs > 0 && book.durationMs > 0 }
            ?.let { stringResource(R.string.book_remaining, formatDuration((book.durationMs - it.positionMs).coerceAtLeast(0))) }
        val facts = listOfNotNull(
            // Episodes: type, season and number from the feed ("Season 2 · Episode 12").
            if (isBook) null else episodeLabel(book, withSeason = true),
            book.addedAt.takeIf { book.kind == BookKind.EPISODE && it > 0 }?.let { formatEpisodeDay(it, withTime = true) },
            book.publishedYear.takeIf { isBook },
            book.publisher.takeIf { isBook },
            book.language?.takeIf { isBook }?.let(::languageName),
            book.durationMs.takeIf { it > 0 }?.let(::formatDuration),
            remaining,
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        // Books from ARD Sounds are often only available for a limited time (`books_improvements.md` M9).
        if (book.isArdSounds) book.availableUntil?.let { AvailabilityText(it, downloaded = download == TrackDownload.DONE) }
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
        // Episode of a podcast that isn't subscribed (concept 12.10): directly above play, a tap leads to the podcast.
        trialNotice?.invoke()
        // Play takes two thirds of the row, download one third (books and episodes); books from the device have no download.
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
        if (onUpload != null) {
            TextButton(onClick = onUpload) {
                Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null)
                Text(stringResource(R.string.ard_upload_open), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (download == TrackDownload.QUEUED || download == TrackDownload.RUNNING) {
            Text(
                if (book.kind == BookKind.EPISODE) episodeTransferText(episodeFraction) else transferText(transfer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (partial != null) {
            Text(
                pluralStringResource(R.plurals.book_download_partial, partial.second, partial.first, partial.second),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Download; while downloading cancel, afterwards delete (with confirmation), after an error retry. */
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
    // Narrow button: smaller paddings, so "Download" and "Cancel" fit even with large font.
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(painterResource(icon), contentDescription = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "32 MB of 500 MB downloaded"; without a known total size only the transferred part, before the start "Waiting". */
@Composable
internal fun transferText(transfer: BookTransfer?): String = when {
    transfer == null -> stringResource(R.string.book_download_waiting)
    transfer.totalBytes == null -> stringResource(R.string.book_download_progress_unknown, formatBytes(transfer.doneBytes))
    else -> stringResource(R.string.book_download_progress, formatBytes(transfer.doneBytes), formatBytes(transfer.totalBytes!!))
}

/** "45 % downloaded"; before the start and without a known file size "Waiting…". */
@Composable
private fun episodeTransferText(fraction: Float?): String =
    if (fraction == null || fraction == EpisodeDownloadProgress.UNKNOWN) {
        stringResource(R.string.book_download_waiting)
    } else {
        stringResource(R.string.episode_download_progress, (fraction * 100).toInt())
    }

/**
 * Description, contributors (episodes) and chapter list; a tap on a chapter plays from its start, for episodes likewise
 * a tap on a timestamp in the show notes.
 */
private fun LazyListScope.bookDetailItems(book: Book, chapters: List<BookChapter>, persons: List<EpisodePerson>, onChapter: (Long) -> Unit) {
    if (book.description.isNotBlank()) {
        item(key = "description") {
            Text(
                rememberDescription(book.description, book.durationMs, onChapter.takeIf { book.kind == BookKind.EPISODE }),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
    }
    if (persons.isNotEmpty()) {
        item(key = "persons") { EpisodePersons(persons, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) }
    }
    if (chapters.isNotEmpty()) {
        item(key = "chapters-header") {
            Text(
                stringResource(R.string.spoken_chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = GroupMargin + 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        val withImages = chapters.any { it.imageUrl != null }
        groupedItems(chapters, key = { it.index }) { chapter ->
            ListItem(
                headlineContent = { Text(chapterTitle(chapter)) },
                supportingContent = if (chapter.unavailable) ({ Text(stringResource(R.string.ard_books_part_gone)) }) else null,
                leadingContent = if (withImages) ({ ChapterImage(chapter.imageUrl) }) else null,
                trailingContent = {
                    ChapterTrailing(chapter.url) { Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.bodySmall) }
                },
                colors = unavailableChapterColors(chapter),
                modifier = Modifier.clickable(enabled = !chapter.unavailable) { onChapter(chapter.startMs) },
            )
        }
    }
}

/**
 * Authors or narrators as a line "A, B, C" that wraps if needed; a name for which [opens] holds leads on tap to
 * its page ([onClick]).
 */
@Composable
private fun PeopleLine(
    names: List<String>,
    opens: (String) -> Boolean,
    clickLabel: String,
    style: TextStyle,
    onClick: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        names.forEachIndexed { index, name ->
            Text(
                if (index < names.lastIndex) "$name," else name,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (opens(name)) Modifier.clickable(onClickLabel = clickLabel) { onClick(name) } else Modifier,
            )
        }
    }
}

/** "The Hunger Games · Book 2"; without a volume just the name. */
@Composable
private fun seriesLabel(series: SeriesRef): String {
    val volume = series.volume ?: return series.name
    return stringResource(R.string.book_series_volume, series.name, volumeNumber(volume))
}

/** Whether the subtitle only names one of the series including volume ("Carl Mørck 10", "Carl Mørck, Band 10"). */
internal fun isSeriesOnly(subtitle: String, series: String): Boolean {
    fun plain(text: String) = text.lowercase().filter { it.isLetterOrDigit() }.replace("band", "").replace("book", "")
    val wanted = plain(subtitle)
    return parseSeries(series).any { plain("${it.name}${it.volume?.let(::volumeNumber).orEmpty()}") == wanted }
}

/**
 * A volume for display: a bare number without ".0" and leading zeros, "2.5" or "2,5" depending on the language; everything else
 * ("2a", "1-3") stays as it is.
 */
internal fun volumeNumber(volume: String): String {
    val number = volume.trim().takeIf { it.matches(PLAIN_NUMBER) }?.replace(',', '.')?.toDouble() ?: return volume
    return if (number % 1.0 == 0.0) number.toLong().toString() else NumberFormat.getInstance().format(number)
}

private val PLAIN_NUMBER = Regex("""\d+(?:[.,]\d+)?""")

/**
 * A book's language in the app's language: Audiobookshelf mostly stores it in English ("German") or as a code ("de");
 * anything unknown stays as it is.
 */
internal fun languageName(language: String): String {
    val trimmed = language.trim()
    val locale = Locale.getAvailableLocales().firstOrNull {
        it.getDisplayLanguage(Locale.ENGLISH).equals(trimmed, ignoreCase = true) || it.language.equals(trimmed, ignoreCase = true)
    } ?: return trimmed
    return locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** A chapter in a file that is no longer available ([BookChapter.unavailable]) appears grayed out. */
@Composable
internal fun unavailableChapterColors(chapter: BookChapter, containerColor: Color = Color.Transparent): ListItemColors {
    if (!chapter.unavailable) return ListItemDefaults.colors(containerColor = containerColor)
    val dimmed = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    return ListItemDefaults.colors(
        containerColor = containerColor,
        headlineColor = dimmed,
        supportingColor = dimmed,
        trailingIconColor = dimmed,
    )
}

private const val DISABLED_ALPHA = 0.38f

@Composable
internal fun chapterTitle(chapter: BookChapter): String =
    chapter.title.ifBlank { stringResource(R.string.spoken_chapter_n, chapter.index + 1) }

/** Chapter image before the title in chapter lists; without an image the space stays empty so the titles stay aligned. */
@Composable
internal fun ChapterImage(url: String?) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** On the right in chapter lists: [time], preceded by the chapter's web page if it has one. */
@Composable
internal fun ChapterTrailing(url: String?, time: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (url != null) ChapterLinkButton(url)
        time()
    }
}

/** Opens a chapter's web page in the browser. */
@Composable
internal fun ChapterLinkButton(url: String) {
    val openLink = rememberOpenLink()
    IconButton(onClick = { openLink(url) }) {
        Icon(painterResource(DesignR.drawable.ic_open_in_new), contentDescription = stringResource(R.string.spoken_chapter_link))
    }
}

/** Opens a web page; without a browser nothing happens. */
@Composable
internal fun rememberOpenLink(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> runCatching { uriHandler.openUri(url) } } }
}
