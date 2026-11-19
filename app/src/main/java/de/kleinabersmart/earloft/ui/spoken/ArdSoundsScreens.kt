package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.ArdSubscription
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBook
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBookSummary
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdCategory
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdShow
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Seite „ARD Sounds“ in *Bücher* (`books_improvements.md` M9): oben die Suche nach Titeln (erst auf die Lupe), darunter die
 * Sendungen der Rubriken Hörbuch und Hörspiel. Eine Sendung ist ein Buch in Teilen oder eine Reihe mit vielen Büchern; ihre
 * Seite zeigt die Bücher. Die Chips der Rubriken bleiben beim Scrollen oben stehen, damit sich die Rubrik jederzeit wechseln lässt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArdSoundsScreen(
    onBack: () -> Unit,
    onShow: (ArdShow) -> Unit,
    onBook: (ArdBookSummary) -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArdSoundsViewModel = hiltViewModel(),
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val subscribed by viewModel.subscribed.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    var unsubscribing by remember { mutableStateOf<ArdSubscription?>(null) }
    val category by viewModel.category.collectAsStateWithLifecycle()
    val shows by viewModel.shows.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    var term by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val startSearch = {
        focus.clearFocus()
        viewModel.search(term)
    }
    // Ohne Suchbegriff wieder die Rubriken.
    LaunchedEffect(term.isBlank()) { if (term.isBlank()) viewModel.clearSearch() }
    val openBook: (ArdBookSummary) -> Unit = { book -> library[book.key]?.let(onOpenBook) ?: onBook(book) }
    val noResults = search?.let { stringResource(R.string.search_no_results, it.first) }.orEmpty()

    unsubscribing?.let { subscription ->
        AlertDialog(
            onDismissRequest = { unsubscribing = null },
            title = { Text(stringResource(R.string.ard_books_unsubscribe)) },
            text = { Text(stringResource(R.string.ard_books_unsubscribe_confirm, subscription.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unsubscribe(subscription.showId)
                    unsubscribing = null
                }) { Text(stringResource(R.string.ard_books_unsubscribe)) }
            },
            dismissButton = { TextButton(onClick = { unsubscribing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.directory_ard_sounds), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        FastScrollLazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(stringResource(R.string.ard_books_search_label)) },
                    trailingIcon = {
                        IconButton(onClick = startSearch, enabled = term.isNotBlank()) {
                            Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_directory_search))
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (term.isNotBlank()) startSearch() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = GroupMargin),
                )
            }
            val current = search
            if (current != null) {
                item(key = "results-header") { SectionHeader(stringResource(R.string.ard_books_results)) }
                ardBookList(current.second, library, empty = noResults, onBook = openBook, keyPrefix = "hit")
            } else {
                if (subscriptions.isNotEmpty()) {
                    item(key = "mine-header") { SectionHeader(stringResource(R.string.ard_books_my_series)) }
                    groupedItems(subscriptions, key = { "mine-${it.showId}" }) { subscription ->
                        ListItem(
                            headlineContent = { Text(subscription.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Icon(painterResource(DesignR.drawable.ic_notifications), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                IconButton(onClick = { unsubscribing = subscription }) {
                                    Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.ard_books_unsubscribe))
                                }
                            },
                            modifier = Modifier.clickable { onShow(ArdShow(subscription.showId, subscription.title, publisher = "", imageUrl = null, itemCount = 0)) },
                        )
                    }
                }
                stickyHeader(key = CategoriesKey) {
                    // Eigener Hintergrund, damit die Sendungen darunter durchlaufen statt durchzuscheinen.
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ArdCategory.entries.forEach { entry ->
                            FilterChip(
                                selected = entry == category,
                                onClick = {
                                    viewModel.selectCategory(entry)
                                    scope.launch { listState.showCategoriesStart() }
                                },
                                label = { Text(stringResource(entry.label)) },
                            )
                        }
                    }
                }
                when (val state = shows) {
                    // So hoch wie die Liste: Beim Laden der anderen Rubrik bleiben die Chips oben stehen, statt dass die
                    // kurze Liste zurück an den Seitenanfang springt.
                    ArdLoad.Loading -> item(key = "shows-loading") { Box(Modifier.fillParentMaxHeight()) { ArdLoading() } }
                    ArdLoad.Failed -> item(key = "shows-failed") { Box(Modifier.fillParentMaxHeight()) { ArdFailed(viewModel::retry) } }
                    is ArdLoad.Done -> groupedItems(state.value, key = { "show-${it.id}" }) { show -> ArdShowRow(show, subscribed = show.id in subscribed) { onShow(show) } }
                }
            }
        }
    }
}

private const val CategoriesKey = "categories"

/**
 * Nach einem Rubrikwechsel beginnt die Liste wieder direkt unter den Chips, wenn sie schon darunter gescrollt war;
 * sonst stünde man mitten in der neuen Liste (oder hinter ihrem Ende).
 */
private suspend fun LazyListState.showCategoriesStart() {
    val header = layoutInfo.visibleItemsInfo.firstOrNull { it.key == CategoriesKey } ?: return
    val pastHeader = firstVisibleItemIndex > header.index || (firstVisibleItemIndex == header.index && firstVisibleItemScrollOffset > 0)
    if (pastHeader) scrollToItem(header.index)
}

/** Die Bücher einer Sendung aus ARD Sounds, neueste zuerst. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArdShowScreen(
    showId: String,
    title: String,
    onBack: () -> Unit,
    onBook: (ArdBookSummary) -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArdShowViewModel = hiltViewModel<ArdShowViewModel, ArdShowViewModel.Factory> { it.create(showId) },
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val subscribed by viewModel.subscribed.collectAsStateWithLifecycle()
    val noBooks = stringResource(R.string.ard_books_none)
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.subscription.collect { result ->
            val message = when (result) {
                ArdShowViewModel.SubscriptionResult.SUBSCRIBED -> resources.getString(R.string.ard_books_subscribe_done, title)
                ArdShowViewModel.SubscriptionResult.UNSUBSCRIBED -> resources.getString(R.string.ard_books_unsubscribe_done)
                ArdShowViewModel.SubscriptionResult.FAILED -> resources.getString(R.string.ard_books_subscribe_failed)
            }
            snackbars.show(message, SnackbarDuration.Long)
        }
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (subscribed) {
                    FilledTonalButton(onClick = { viewModel.toggleSubscription(title) }) {
                        Icon(painterResource(DesignR.drawable.ic_notifications), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.ard_books_subscribed), modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.toggleSubscription(title) }) { Text(stringResource(R.string.ard_books_subscribe)) }
                }
                Spacer(Modifier.width(8.dp))
            },
        )
        FastScrollLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            when (val state = books) {
                ArdLoad.Failed -> item(key = "failed") { ArdFailed(viewModel::load) }
                else -> ardBookList(
                    state,
                    library,
                    empty = noBooks,
                    onBook = { book -> library[book.key]?.let(onOpenBook) ?: onBook(book) },
                    keyPrefix = "book",
                    // Alle aus derselben Sendung: deren Name stünde in jeder Zeile.
                    withShow = false,
                )
            }
        }
    }
}

/**
 * Vorschau eines Buchs aus ARD Sounds: Cover, Angaben, Verfügbarkeit, Teile. Abspielen, Laden und Hinzufügen nehmen es in die
 * Bibliothek auf; dann tritt die Buchseite an die Stelle der Vorschau ([onAdded]). Ist es schon dort, gleich die Buchseite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArdBookPreviewScreen(
    key: String,
    title: String,
    onBack: () -> Unit,
    onAdded: (bookId: Long, openPlayer: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArdBookPreviewViewModel = hiltViewModel<ArdBookPreviewViewModel, ArdBookPreviewViewModel.Factory> { it.create(key) },
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(library[key]) { library[key]?.let { onAdded(it, false) } }
    LaunchedEffect(viewModel) {
        viewModel.added.collect { added ->
            added.message?.let { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
            onAdded(added.bookId, added.openPlayer)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.failed.collect { snackbars.show(resources.getString(R.string.ard_books_add_failed), SnackbarDuration.Long) }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        when (val state = book) {
            ArdLoad.Loading -> ArdLoading()
            ArdLoad.Failed -> ArdFailed(viewModel::load)
            is ArdLoad.Done -> {
                val loaded = state.value
                if (loaded == null) {
                    EmptyHint(stringResource(R.string.ard_books_gone))
                } else {
                    FastScrollLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(key = "header") { ArdBookHeader(loaded, working, viewModel::perform) }
                        if (loaded.description.isNotBlank()) {
                            item(key = "description") {
                                Text(
                                    loaded.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 16.dp),
                                )
                            }
                        }
                        if (loaded.parts.size > 1) {
                            item(key = "parts-header") { SectionHeader(stringResource(R.string.spoken_chapters)) }
                            groupedItems(loaded.parts, key = { "part-${it.id}" }) { part ->
                                ListItem(
                                    headlineContent = { Text(part.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { Text(formatDuration(part.durationMs), style = MaterialTheme.typography.bodySmall) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArdBookHeader(book: ArdBook, working: Boolean, onAction: (ArdBookAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PodcastArt(book.imageUrl, Modifier.widthIn(max = 240.dp).fillMaxWidth())
        Text(book.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        if (book.author.isNotBlank()) {
            Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (book.narrator.isNotBlank()) {
            Text(stringResource(R.string.ard_books_read_by, book.narrator), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        val facts = listOfNotNull(
            book.publisher.ifBlank { null },
            book.durationMs.takeIf { it > 0 }?.let(::formatDuration),
            pluralStringResource(R.plurals.ard_books_parts, book.parts.size, book.parts.size).takeIf { book.parts.size > 1 },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        book.availableUntil?.let { AvailabilityText(it, downloaded = false) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onAction(ArdBookAction.PLAY) }, enabled = !working, modifier = Modifier.weight(2f)) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null)
                Text(stringResource(R.string.spoken_play), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            OutlinedButton(onClick = { onAction(ArdBookAction.DOWNLOAD) }, enabled = !working, modifier = Modifier.weight(1f)) {
                Icon(painterResource(DesignR.drawable.ic_download), contentDescription = stringResource(R.string.action_download))
            }
        }
        TextButton(onClick = { onAction(ArdBookAction.ADD) }, enabled = !working) {
            Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
            Text(stringResource(R.string.ard_books_add), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * „Verfügbar bis 08.01.2027“ bzw. „Nicht mehr bei ARD Sounds verfügbar“; in Rot, wenn weniger als eine Woche bleibt und nicht
 * alles geladen ist. Ganz geladene Bücher bleiben hörbar, das steht dann dabei.
 */
@Composable
fun AvailabilityText(until: Long, downloaded: Boolean, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val text = when {
        until <= now && downloaded -> stringResource(R.string.ard_books_expired_downloaded)
        until <= now -> stringResource(R.string.ard_books_expired)
        downloaded -> stringResource(R.string.ard_books_available_until_downloaded, formatEpisodeDay(until, withTime = false))
        else -> stringResource(R.string.ard_books_available_until, formatEpisodeDay(until, withTime = false))
    }
    val urgent = !downloaded && until - now < SOON_MS
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

private const val SOON_MS = 7L * 24 * 60 * 60 * 1000

private fun LazyListScope.ardBookList(
    state: ArdLoad<List<ArdBookSummary>>,
    library: Map<String, Long>,
    empty: String,
    onBook: (ArdBookSummary) -> Unit,
    keyPrefix: String,
    withShow: Boolean = true,
) {
    when (state) {
        ArdLoad.Loading -> item(key = "$keyPrefix-loading") { ArdLoading() }
        ArdLoad.Failed -> item(key = "$keyPrefix-failed") { ArdFailed(null) }
        is ArdLoad.Done -> {
            if (state.value.isEmpty()) item(key = "$keyPrefix-empty") { EmptyHint(empty) }
            groupedItems(state.value, key = { "$keyPrefix-${it.key}" }) { book -> ArdBookRow(book, inLibrary = book.key in library, withShow) { onBook(book) } }
        }
    }
}

@Composable
private fun ArdShowRow(show: ArdShow, subscribed: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(show.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(show.publisher.ifBlank { null }, pluralStringResource(R.plurals.ard_books_titles, show.itemCount, show.itemCount))
                    .joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { PodcastArt(show.imageUrl, Modifier.size(56.dp)) },
        trailingContent = if (subscribed) {
            {
                Icon(
                    painterResource(DesignR.drawable.ic_notifications),
                    contentDescription = stringResource(R.string.ard_books_subscribed),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ArdBookRow(book: ArdBookSummary, inLibrary: Boolean, withShow: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val people = listOfNotNull(book.author.ifBlank { null }, book.narrator.ifBlank { null }?.let { stringResource(R.string.ard_books_read_by, it) })
                if (people.isNotEmpty()) Text(people.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    pluralStringResource(R.plurals.ard_books_parts, book.parts, book.parts).takeIf { book.parts > 1 },
                    book.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
                    book.showTitle.ifBlank { null }?.takeIf { withShow },
                )
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = { PodcastArt(book.imageUrl, Modifier.size(56.dp)) },
        trailingContent = if (inLibrary) {
            {
                Icon(
                    painterResource(DesignR.drawable.ic_check),
                    contentDescription = stringResource(R.string.ard_books_in_library),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ArdLoading() {
    Row(Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.ard_books_loading), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ArdFailed(onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = GroupMargin + 16.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.ard_books_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.lyrics_retry)) }
    }
}

private val ArdCategory.label: Int
    get() = when (this) {
        ArdCategory.AUDIOBOOK -> R.string.ard_books_category_audiobook
        ArdCategory.RADIO_PLAY -> R.string.ard_books_category_radio_play
    }
