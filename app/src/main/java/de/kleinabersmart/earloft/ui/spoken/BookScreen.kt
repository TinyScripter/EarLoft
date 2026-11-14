package de.kleinabersmart.earloft.ui.spoken

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
import de.kleinabersmart.earloft.core.model.BookKind
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

/** Buchseite: Beschreibung, Fortschritt, Abspielen und Kapitelliste (Konzept 12.5). */
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
            // Folgen und Bücher einzeln auf die Uhr (Wear-Konzept 16.1/16.2); ⋮ mit gespielt/ungespielt, Warteschlange usw.
            // Folgen zuerst mit dem Stern („Gemerkt“).
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
            // Aus der Bibliothek entfernt (ARD Sounds): die Seite schließen.
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
                // Eine Folge ist klein und wird wie in der Folgenliste ohne Rückfrage gelöscht.
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
                        bookDetailItems(book, chapters, persons, onChapter = viewModel::playFrom)
                        bookmarkItems(bookmarks, bookmarkActions, onJump = viewModel::playFrom)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
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

/** Cover, Titel, Autor, Dauer, Fortschritt und Abspielen. */
@Composable
private fun BookHeader(
    book: Book,
    progress: BookProgress?,
    download: TrackDownload,
    /** Teilweise geladen: fertige und alle Dateien. */
    partial: Pair<Int, Int>?,
    transfer: BookTransfer?,
    episodeFraction: Float?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onOpenGroup: (BookGroupKind, String) -> Unit,
    /** Bücher aus ARD Sounds: „In Audiobookshelf hochladen“ (`null` = nicht anbieten). */
    onUpload: (() -> Unit)? = null,
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
        // Viele Untertitel nennen nur Serie und Band („Carl Mørck 10“), die darunter ohnehin stehen, oder stecken schon im Titel.
        book.subtitle?.takeUnless { isSeriesOnly(it, book.series) || book.title.contains(it, ignoreCase = true) }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        // Bei Büchern führen Autoren, Serie und Sprecher einzeln zu deren Seite (Bibliothek → Autoren bzw. Serien);
        // Folgen nennen den Podcast.
        val isBook = book.kind == BookKind.BOOK
        if (isBook) {
            // Autoren zuerst, Übersetzer u. Ä. danach und ohne eigene Seite.
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
        // Genres von Audiobookshelf, jedes führt zu den Büchern dieses Genres.
        if (isBook && book.genres.isNotEmpty()) {
            PeopleLine(
                names = book.genres,
                opens = { true },
                clickLabel = stringResource(R.string.book_open_genre),
                style = MaterialTheme.typography.bodyMedium,
                onClick = { onOpenGroup(BookGroupKind.GENRE, it) },
            )
        }
        // Folgen: Erscheinen mit Uhrzeit (Nachrichten kommen mehrmals am Tag unter gleichem Titel). Podcast-Feeds nennen
        // nicht immer eine Dauer.
        // Bücher: Jahr, Verlag, Sprache und Dauer; bei angefangenen die Restzeit (Inhaltszeit, ohne Tempo).
        val remaining = progress?.takeIf { isBook && !it.finished && it.positionMs > 0 && book.durationMs > 0 }
            ?.let { stringResource(R.string.book_remaining, formatDuration((book.durationMs - it.positionMs).coerceAtLeast(0))) }
        val facts = listOfNotNull(
            // Folgen: Art, Staffel und Nummer aus dem Feed („Staffel 2 · Folge 12“).
            if (isBook) null else episodeLabel(book, withSeason = true),
            book.addedAt.takeIf { book.kind == BookKind.EPISODE && it > 0 }?.let { formatEpisodeDay(it, withTime = true) },
            book.publishedYear.takeIf { isBook },
            book.publisher.takeIf { isBook },
            book.language?.takeIf { isBook }?.let(::languageName),
            book.durationMs.takeIf { it > 0 }?.let(::formatDuration),
            remaining,
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        // Bücher aus ARD Sounds sind oft nur befristet abrufbar (`books_improvements.md` M9).
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
                stringResource(R.string.book_download_partial, partial.first, partial.second),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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

/**
 * Beschreibung, Mitwirkende (Folgen) und Kapitelliste; ein Tipp auf ein Kapitel spielt ab dessen Anfang, bei Folgen ebenso
 * ein Tipp auf eine Zeitmarke in den Shownotes.
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
                leadingContent = if (withImages) ({ ChapterImage(chapter.imageUrl) }) else null,
                trailingContent = {
                    ChapterTrailing(chapter.url) { Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.bodySmall) }
                },
                modifier = Modifier.clickable { onChapter(chapter.startMs) },
            )
        }
    }
}

/**
 * Autoren bzw. Sprecher als Zeile „A, B, C“, die bei Bedarf umbricht; ein Name, für den [opens] gilt, führt per Tipp zu
 * seiner Seite ([onClick]).
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

/** „Die Tribute von Panem · Band 2“; ohne Band nur der Name. */
@Composable
private fun seriesLabel(series: SeriesRef): String {
    val sequence = series.sequence ?: return series.name
    return stringResource(R.string.book_series_volume, series.name, volumeNumber(sequence))
}

/** Ob der Untertitel nur eine der Serien samt Band nennt („Carl Mørck 10“, „Carl Mørck, Band 10“). */
internal fun isSeriesOnly(subtitle: String, series: String): Boolean {
    fun plain(text: String) = text.lowercase().filter { it.isLetterOrDigit() }.replace("band", "").replace("book", "")
    val wanted = plain(subtitle)
    return parseSeries(series).any { plain("${it.name}${it.sequence?.let(::volumeNumber).orEmpty()}") == wanted }
}

/** Band ohne „.0“; „2.5“ bleibt „2,5“ bzw. „2.5“ je nach Sprache. */
internal fun volumeNumber(sequence: Double): String =
    if (sequence % 1.0 == 0.0) sequence.toLong().toString() else NumberFormat.getInstance().format(sequence)

/**
 * Die Sprache eines Buchs in der Sprache der App: Audiobookshelf führt sie meist englisch („German“) oder als Code („de“);
 * Unbekanntes bleibt, wie es ist.
 */
internal fun languageName(language: String): String {
    val trimmed = language.trim()
    val locale = Locale.getAvailableLocales().firstOrNull {
        it.getDisplayLanguage(Locale.ENGLISH).equals(trimmed, ignoreCase = true) || it.language.equals(trimmed, ignoreCase = true)
    } ?: return trimmed
    return locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

@Composable
internal fun chapterTitle(chapter: BookChapter): String =
    chapter.title.ifBlank { stringResource(R.string.spoken_chapter_n, chapter.index + 1) }

/** Kapitelbild vor dem Titel in Kapitellisten; ohne Bild bleibt der Platz leer, damit die Titel bündig stehen. */
@Composable
internal fun ChapterImage(url: String?) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Rechts in Kapitellisten: [time], davor die Webseite des Kapitels, falls es eine hat. */
@Composable
internal fun ChapterTrailing(url: String?, time: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (url != null) ChapterLinkButton(url)
        time()
    }
}

/** Öffnet die Webseite eines Kapitels im Browser. */
@Composable
internal fun ChapterLinkButton(url: String) {
    val openLink = rememberOpenLink()
    IconButton(onClick = { openLink(url) }) {
        Icon(painterResource(DesignR.drawable.ic_open_in_new), contentDescription = stringResource(R.string.spoken_chapter_link))
    }
}

/** Öffnet eine Webseite; ohne Browser passiert nichts. */
@Composable
internal fun rememberOpenLink(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) { { url -> runCatching { uriHandler.openUri(url) } } }
}
