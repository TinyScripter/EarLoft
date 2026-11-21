package de.kleinabersmart.earloft.ui.spoken

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import de.kleinabersmart.earloft.ui.components.GroupedItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import de.kleinabersmart.earloft.core.data.share.IncomingFeed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.network.podcast.directory.DirectoryPodcast
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastCategory
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll
import java.util.Locale
import de.kleinabersmart.earloft.core.network.podcast.directory.feedKey
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.CardGap
import de.kleinabersmart.earloft.ui.components.cardItems
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * "Add podcast" page (concept 12.10): one field at the top, for a search in the enabled directories or a feed address
 * ([looksLikeFeedAddress]; then a card to subscribe instead of results). The search only runs on the magnifier (or the
 * keyboard's search key). As long as nothing has been searched, the popular podcasts of the directories with charts
 * ("Discover") are shown below, with categories as chips. The OPML import is in the menu at the top right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPodcastScreen(
    onBack: () -> Unit,
    onFeedAdded: (String) -> Unit,
    onOpenFeed: (String) -> Unit,
    onPreview: (DirectoryPodcast) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddPodcastViewModel = hiltViewModel(),
) {
    val add by viewModel.add.collectAsStateWithLifecycle()
    val directories by viewModel.directories.collectAsStateWithLifecycle()
    val subscribed by viewModel.subscribed.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val discoverable by viewModel.discoverable.collectAsStateWithLifecycle()
    val country by viewModel.country.collectAsStateWithLifecycle()
    val discover by viewModel.discover.collectAsStateWithLifecycle()
    var term by rememberSaveable { mutableStateOf("") }
    var opmlToImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    /** Result (by [feedKey]) for which the user is currently choosing whether the subscription runs via the feed or via ARD Sounds. */
    var choosingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingDirectories by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { opmlToImport = it }
    val focus = LocalFocusManager.current
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(add) {
        val state = add
        if (state is AddPodcastState.Done) {
            viewModel.resetAdd()
            onFeedAdded(state.feedId)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.opmlResults.collect { snackbars.show(opmlImportMessage(resources, it), SnackbarDuration.Long) }
    }
    val startSearch = {
        focus.clearFocus()
        viewModel.search(term)
    }
    // Without a search term, back to the popular podcasts; they load as soon as a directory with charts is enabled.
    LaunchedEffect(term.isBlank()) { if (term.isBlank()) viewModel.clearSearch() }
    LaunchedEffect(discoverable) { discoverable?.let { viewModel.discover(viewModel.discover.value?.category, it) } }
    val openHit: (DirectoryPodcast) -> Unit = { hit ->
        // The result counts as subscribed if it is one of the two sources; then it opens the subscription right away.
        val feedId = subscribed[feedKey(hit.feedUrl)] ?: hit.ardSoundsUrl?.let { subscribed[feedKey(it)] }
        when {
            feedId != null -> onOpenFeed(feedId)
            hit.ardSoundsUrl != null -> choosingSource = feedKey(hit.feedUrl)
            else -> onPreview(hit)
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.podcast_add), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
            // OPML is a one-off when switching apps; it waits in the menu instead of taking room on the page.
            actions = {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_opml_import)) },
                            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }
            },
        )
        val enabled = directories
        // One field for both (user, 2026-11-21): a feed address subscribes, anything else searches the directories.
        val address = term.takeIf(::looksLikeFeedAddress)?.trim()
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            // With search results, the field stays pinned at the top while scrolling (user, 2026-11-21); while discovering,
            // only the categories are pinned ([DiscoverHeader]). Same key in both cases, so the field keeps its state.
            val searchField: @Composable () -> Unit = {
                SearchOrAddressField(
                    term = term,
                    onTermChange = {
                        term = it
                        if (add is AddPodcastState.Failed) viewModel.resetAdd()
                    },
                    address = address,
                    canSearch = !enabled.isNullOrEmpty(),
                    directories = enabled.orEmpty(),
                    onSearch = startSearch,
                    onSubscribe = { address?.let { viewModel.subscribe(IncomingFeed.fromLink(it) ?: it) } },
                    onChooseDirectories = { choosingDirectories = true },
                )
            }
            if (search == DirectorySearchState.Idle || address != null) {
                item(key = SearchFieldKey) { searchField() }
            } else {
                stickyHeader(key = SearchFieldKey) { searchField() }
            }
            when {
                address != null -> item(key = "address") {
                    FeedAddressCard(address, add, onSubscribe = { viewModel.subscribe(IncomingFeed.fromLink(address) ?: address) })
                }
                enabled == null -> Unit
                enabled.isEmpty() -> item(key = "no-directory") {
                    TrialNotice(
                        text = stringResource(R.string.podcast_directory_none),
                        modifier = Modifier.padding(horizontal = GroupMargin, vertical = 8.dp),
                    ) {
                        FilledTonalButton(onClick = { choosingDirectories = true }) { Text(stringResource(R.string.podcast_directories_choose)) }
                    }
                }
                else -> when (val state = search) {
                    DirectorySearchState.Idle -> discoverable?.takeIf { it.isNotEmpty() }?.let { directories ->
                        stickyHeader(key = DiscoverHeaderKey) {
                            DiscoverHeader(directories, country, discover?.category) {
                                viewModel.discover(it, directories)
                                scope.launch { listState.showDiscoverStart() }
                            }
                        }
                        discoverSection(directories, discover, subscribed, onHit = openHit)
                    }
                    DirectorySearchState.Searching -> item(key = "searching") {
                        Row(Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.podcast_directory_searching), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    is DirectorySearchState.Done -> {
                        if (state.failed.isNotEmpty()) {
                            item(key = "failed") {
                                Text(
                                    stringResource(
                                        R.string.podcast_directory_failed,
                                        state.failed.sorted().joinToString(", ") { resources.getString(it.label) },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (state.hits.isEmpty() && state.failed.size < enabled.size) {
                            item(key = "empty") { EmptyHint(stringResource(R.string.search_no_results, state.term)) }
                        }
                        cardItems(state.hits, key = { "hit-${feedKey(it.feedUrl)}" }, gap = CardGap) { hit ->
                            DirectoryHitRow(hit, subscribed = hit.isSubscribed(subscribed)) { openHit(hit) }
                        }
                    }
                }
            }
        }
    }

    opmlToImport?.let { uri ->
        OpmlImportDialog(
            onImport = { replace ->
                viewModel.importOpml(uri, replace)
                opmlToImport = null
            },
            onDismiss = { opmlToImport = null },
        )
    }
    if (choosingDirectories) {
        DirectoriesDialog(
            available = viewModel.availableDirectories,
            enabled = directories.orEmpty(),
            onToggle = viewModel::setDirectory,
            onOpenSettings = {
                choosingDirectories = false
                onOpenSettings()
            },
            onDismiss = { choosingDirectories = false },
        )
    }
    choosingSource?.let { key ->
        // A new search replaces the results; if the selected one is gone, the dialog stays closed.
        val shown = (search as? DirectorySearchState.Done)?.hits ?: (discover as? DiscoverState.Done)?.hits
        shown?.firstOrNull { feedKey(it.feedUrl) == key }?.let { hit ->
            SourceDialog(
                hit,
                onChoose = { url ->
                    choosingSource = null
                    onPreview(hit.copy(feedUrl = url))
                },
                onDismiss = { choosingSource = null },
            )
        }
    }
    (add as? AddPodcastState.Archived)?.let { archived ->
        ReactivateDialog(archived.title, onReactivate = { viewModel.reactivate(archived.feedId) }, onDismiss = viewModel::resetAdd)
    }
    (add as? AddPodcastState.NeedsCredentials)?.let { needed ->
        FeedCredentialsDialog(
            feedUrl = needed.input,
            rejected = needed.rejected,
            initialUsername = needed.username,
            onConfirm = { viewModel.subscribe(needed.input, it) },
            onDismiss = viewModel::resetAdd,
        )
    }
}

private fun DirectoryPodcast.isSubscribed(subscribed: Map<String, String>): Boolean =
    feedKey(feedUrl) in subscribed || ardSoundsUrl?.let { feedKey(it) in subscribed } == true

/** Search field of the directory search; pinned at the top once something has been searched. */
private const val SearchFieldKey = "search"

private const val DiscoverHeaderKey = "discover-header"

/**
 * After a category change the list starts again directly below the chips if it was already scrolled past them;
 * otherwise you'd end up in the middle of the new list (or past its end).
 */
private suspend fun LazyListState.showDiscoverStart() {
    val header = layoutInfo.visibleItemsInfo.firstOrNull { it.key == DiscoverHeaderKey } ?: return
    val pastHeader = firstVisibleItemIndex > header.index || (firstVisibleItemIndex == header.index && firstVisibleItemScrollOffset > 0)
    if (pastHeader) scrollToItem(header.index)
}

/**
 * Heading and categories of "Discover" below the empty search field (concept 12.10): "Popular in <country>" (with Apple,
 * otherwise "Trending" from Podcast Index) and the categories as chips. Pinned at the top while scrolling, so the category
 * can be changed at any time.
 */
@Composable
private fun DiscoverHeader(
    directories: List<PodcastDirectory>,
    country: String?,
    selected: PodcastCategory?,
    onCategory: (PodcastCategory?) -> Unit,
) {
    val title = if (PodcastDirectory.APPLE in directories && country != null) {
        stringResource(R.string.podcast_discover_popular, Locale.Builder().setRegion(country).build().displayCountry)
    } else {
        stringResource(R.string.podcast_discover_trending)
    }
    // Own background, so the results below scroll underneath instead of showing through.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp)) {
        SectionHeader(title)
        val scroll = rememberScrollState()
        Row(
            Modifier.keepHorizontalScroll(scroll).horizontalScroll(scroll).padding(horizontal = GroupMargin + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = selected == null, onClick = { onCategory(null) }, label = { Text(stringResource(R.string.podcast_category_all)) })
            PodcastCategory.entries.forEach { category ->
                FilterChip(selected = selected == category, onClick = { onCategory(category) }, label = { Text(stringResource(category.label)) })
            }
        }
    }
}

/** Results of "Discover" (the popular podcasts of the selected category) as in the search; the header is [DiscoverHeader]. */
private fun LazyListScope.discoverSection(
    directories: List<PodcastDirectory>,
    state: DiscoverState?,
    subscribed: Map<String, String>,
    onHit: (DirectoryPodcast) -> Unit,
) {
    when (state) {
        // As tall as the list: after a category change the chips stay at the top instead of the short list
        // jumping back to the top of the page while loading.
        null, is DiscoverState.Loading -> item(key = "discover-loading") {
            Box(Modifier.fillParentMaxHeight()) {
                Row(Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.podcast_discover_loading), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        is DiscoverState.Done -> {
            if (state.failed.isNotEmpty()) {
                item(key = "discover-failed") {
                    val resources = LocalResources.current
                    Text(
                        stringResource(R.string.podcast_directory_failed, state.failed.sorted().joinToString(", ") { resources.getString(it.label) }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (state.hits.isEmpty() && state.failed.size < directories.size) {
                item(key = "discover-empty") { EmptyHint(stringResource(R.string.podcast_discover_empty)) }
            }
            cardItems(state.hits, key = { "popular-${feedKey(it.feedUrl)}" }, gap = CardGap) { hit ->
                DirectoryHitRow(hit, subscribed = hit.isSubscribed(subscribed)) { onHit(hit) }
            }
        }
    }
}

private val PodcastCategory.label: Int
    get() = when (this) {
        PodcastCategory.NEWS -> R.string.podcast_category_news
        PodcastCategory.SOCIETY -> R.string.podcast_category_society
        PodcastCategory.COMEDY -> R.string.podcast_category_comedy
        PodcastCategory.HISTORY -> R.string.podcast_category_history
        PodcastCategory.TRUE_CRIME -> R.string.podcast_category_true_crime
        PodcastCategory.SCIENCE -> R.string.podcast_category_science
        PodcastCategory.TECHNOLOGY -> R.string.podcast_category_technology
        PodcastCategory.BUSINESS -> R.string.podcast_category_business
        PodcastCategory.GOVERNMENT -> R.string.podcast_category_government
        PodcastCategory.EDUCATION -> R.string.podcast_category_education
        PodcastCategory.HEALTH -> R.string.podcast_category_health
        PodcastCategory.SPORTS -> R.string.podcast_category_sports
        PodcastCategory.ARTS -> R.string.podcast_category_arts
        PodcastCategory.FICTION -> R.string.podcast_category_fiction
        PodcastCategory.MUSIC -> R.string.podcast_category_music
        PodcastCategory.TV_FILM -> R.string.podcast_category_tv_film
        PodcastCategory.LEISURE -> R.string.podcast_category_leisure
        PodcastCategory.KIDS -> R.string.podcast_category_kids
        PodcastCategory.RELIGION -> R.string.podcast_category_religion
    }

/** Confirmation when subscribing to a podcast whose subscription already exists but is archived. */
@Composable
private fun ReactivateDialog(title: String, onReactivate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_archived_title)) },
        text = { Text(stringResource(R.string.podcast_archived_message, title)) },
        confirmButton = { TextButton(onClick = onReactivate) { Text(stringResource(R.string.podcast_reactivate)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Choice of where a result comes from that both ARD Sounds and its RSS feed provide (concept 12.10): the feed (as in
 * other apps, with the directories that know it) or ARD Sounds (own images and episode list).
 */
/**
 * Turn directories on and off directly on the page, without a detour through the settings (the same setting as
 * Settings → Podcasts → Directories). The country for Apple is only available there.
 */
@Composable
private fun DirectoriesDialog(
    available: List<PodcastDirectory>,
    enabled: List<PodcastDirectory>,
    onToggle: (PodcastDirectory, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_section_directories)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                available.forEach { directory ->
                    val checked = directory in enabled
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle(directory, it) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(stringResource(directory.label), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(directory.summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = checked, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.spoken_close)) } },
        dismissButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.podcast_directory_open_settings)) } },
    )
}

@Composable
private fun SourceDialog(hit: DirectoryPodcast, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val ardSoundsUrl = hit.ardSoundsUrl ?: return
    val host = normalizeFeedUrl(hit.feedUrl)?.host?.removePrefix("www.") ?: hit.feedUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_source_title)) },
        text = {
            Column {
                Text(stringResource(R.string.podcast_source_message, hit.title), modifier = Modifier.padding(bottom = 8.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.podcast_source_feed, host)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.podcast_source_feed_summary,
                                hit.directories.sorted().joinToString(", ") { resources.getString(it.label) },
                            ),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onChoose(hit.feedUrl) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.directory_ard_sounds)) },
                    supportingContent = { Text(stringResource(R.string.podcast_source_ard_sounds_summary)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onChoose(ardSoundsUrl) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The one field of the page: a search term or a feed address ([address], then the keyboard's action subscribes instead of
 * searching). Below it, where the search goes ("Search in Podcast Index · Apple"); a tap there chooses the directories.
 */
@Composable
private fun SearchOrAddressField(
    term: String,
    onTermChange: (String) -> Unit,
    address: String?,
    canSearch: Boolean,
    directories: List<PodcastDirectory>,
    onSearch: () -> Unit,
    onSubscribe: () -> Unit,
    onChooseDirectories: () -> Unit,
) {
    val resources = LocalResources.current
    // Own background, so the results below scroll underneath instead of showing through when pinned.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = GroupMargin)) {
        OutlinedTextField(
            value = term,
            onValueChange = onTermChange,
            placeholder = { Text(stringResource(R.string.podcast_add_search_field), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
            trailingIcon = {
                // Clearing the term also brings back "Discover" (see clearSearch above).
                if (term.isNotEmpty()) {
                    IconButton(onClick = { onTermChange("") }) {
                        Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = if (address != null) ImeAction.Done else ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { if (term.isNotBlank() && canSearch) onSearch() },
                onDone = { onSubscribe() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // With an address nothing is searched, so where the search would go doesn't matter then.
        if (directories.isNotEmpty() && address == null) {
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onChooseDirectories)
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.podcast_search_in, directories.joinToString(" · ") { resources.getString(it.label) }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    painterResource(DesignR.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.podcast_directories_choose),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Instead of results while the field holds a feed address: the address, a hint for `http://`, errors and "Subscribe". */
@Composable
private fun FeedAddressCard(address: String, state: AddPodcastState, onSubscribe: () -> Unit) {
    val working = state == AddPodcastState.Working
    GroupedItem(0, 1, Modifier.padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.podcast_add_url), style = MaterialTheme.typography.titleMedium)
            Text(
                address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (isUnencrypted(IncomingFeed.fromLink(address) ?: address)) {
                Text(
                    stringResource(R.string.podcast_add_http_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state is AddPodcastState.Failed) {
                Text(
                    stringResource(state.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            SubscribeButton(working, onClick = onSubscribe, modifier = Modifier.align(Alignment.End).padding(top = 12.dp))
        }
    }
}

private val ADDRESS_SCHEME = Regex("^(https?|feed|itpc|pcast|podcast):", RegexOption.IGNORE_CASE)

/**
 * Whether the field holds a feed address rather than a search term: one word that starts with a scheme (`https:`, `feed:`,
 * `itpc:` …) or `www.`, or a host with a path (`example.com/feed.xml`). A bare `example.com` stays a search term.
 */
internal fun looksLikeFeedAddress(input: String): Boolean {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return false
    if (ADDRESS_SCHEME.containsMatchIn(text) || text.startsWith("www.", ignoreCase = true)) return true
    return '/' in text && normalizeFeedUrl(text) != null
}

/** "Subscribe"; while loading, a spinner instead of text. */
@Composable
private fun SubscribeButton(working: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = !working, modifier = modifier) {
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.podcast_adding), modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(stringResource(R.string.podcast_add_confirm))
        }
    }
}

/** A result: image, title, author and the directories that know it; subscribed ones with a check mark. */
@Composable
private fun DirectoryHitRow(hit: DirectoryPodcast, subscribed: Boolean, onClick: () -> Unit) {
    val resources = LocalResources.current
    ListItem(
        headlineContent = { Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (hit.author.isNotBlank()) Text(hit.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    hit.directories.sorted().joinToString(" · ") { resources.getString(it.label) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = { PodcastArt(hit.imageUrl, Modifier.size(56.dp)) },
        trailingContent = if (subscribed) {
            {
                Icon(
                    painterResource(DesignR.drawable.ic_check),
                    contentDescription = stringResource(R.string.podcast_directory_subscribed),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Preview of a result (concept 12.10): loads the feed as a trial subscription and then hands over to the regular podcast page
 * ([onOpened] with the id, even if it is already subscribed). Until then, the header with the directory's details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastPreviewScreen(
    feedUrl: String,
    title: String,
    author: String,
    imageUrl: String?,
    onBack: () -> Unit,
    onOpened: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastPreviewViewModel = hiltViewModel<PodcastPreviewViewModel, PodcastPreviewViewModel.Factory> { it.create(feedUrl, imageUrl) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        val opened = state
        if (opened is PreviewState.Opened) onOpened(opened.feedId)
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = {}, navigationIcon = { BackButton(onBack) })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            PodcastArt(imageUrl, Modifier.size(PREVIEW_ART_SIZE))
            Box(Modifier.weight(1f).height(PREVIEW_ART_SIZE).padding(start = 16.dp)) {
                Column(Modifier.align(Alignment.Center).fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (author.isNotBlank()) {
                        Text(
                            author,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        when (val current = state) {
            is PreviewState.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(current.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text(stringResource(R.string.podcast_preview_retry)) }
            }
            // Cancel leads back to the results.
            is PreviewState.Archived -> ReactivateDialog(
                current.title,
                onReactivate = { viewModel.reactivate(current.feedId) },
                onDismiss = onBack,
            )
            else -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.podcast_adding), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private val PREVIEW_ART_SIZE = 144.dp
