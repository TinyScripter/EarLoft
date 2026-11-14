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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.network.podcast.directory.DirectoryPodcast
import de.kleinabersmart.earloft.core.network.podcast.directory.feedKey
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Seite „Podcast hinzufügen“ (Konzept 12.10): oben Feed-Adresse und OPML-Import, darunter die Suche in den eingeschalteten
 * Verzeichnissen. Gesucht wird erst auf die Lupe (oder die Such-Taste der Tastatur).
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
    var url by rememberSaveable { mutableStateOf("") }
    var term by rememberSaveable { mutableStateOf("") }
    var opmlToImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    /** Treffer (nach [feedKey]), für den gerade gewählt wird, ob das Abo über den Feed oder über ARD Sounds läuft. */
    var choosingSource by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { opmlToImport = it }
    val focus = LocalFocusManager.current
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current

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

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.podcast_add), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "direct") { SectionHeader(stringResource(R.string.podcast_add_direct)) }
            item(key = "url") {
                FeedUrlForm(url, onUrlChange = { url = it }, state = add, onSubscribe = { viewModel.subscribe(url) })
            }
            group("opml-group") {
                row("opml") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_opml_import)) },
                        supportingContent = { Text(stringResource(R.string.settings_opml_import_summary)) },
                        leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                        modifier = Modifier.clickable { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }

            item(key = "search-header") { SectionHeader(stringResource(R.string.podcast_add_search)) }
            val enabled = directories
            when {
                enabled == null -> Unit
                enabled.isEmpty() -> item(key = "no-directory") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = GroupMargin + 16.dp)) {
                        Text(
                            stringResource(R.string.podcast_directory_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.podcast_directory_open_settings))
                        }
                    }
                }
                else -> {
                    item(key = "search") {
                        OutlinedTextField(
                            value = term,
                            onValueChange = { term = it },
                            label = { Text(stringResource(R.string.podcast_directory_search_label)) },
                            supportingText = {
                                Text(enabled.joinToString(" · ") { resources.getString(it.label) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
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
                    when (val state = search) {
                        DirectorySearchState.Idle -> Unit
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
                            groupedItems(state.hits, key = { "hit-${feedKey(it.feedUrl)}" }) { hit ->
                                // Abonniert ist der Treffer, wenn es eine der beiden Quellen ist; dann öffnet er gleich das Abo.
                                val feedId = subscribed[feedKey(hit.feedUrl)] ?: hit.ardSoundsUrl?.let { subscribed[feedKey(it)] }
                                DirectoryHitRow(hit, subscribed = feedId != null) {
                                    when {
                                        feedId != null -> onOpenFeed(feedId)
                                        hit.ardSoundsUrl != null -> choosingSource = feedKey(hit.feedUrl)
                                        else -> onPreview(hit)
                                    }
                                }
                            }
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
    choosingSource?.let { key ->
        // Eine neue Suche ersetzt die Treffer; ist der gewählte weg, bleibt der Dialog zu.
        (search as? DirectorySearchState.Done)?.hits?.firstOrNull { feedKey(it.feedUrl) == key }?.let { hit ->
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

/** Rückfrage, wenn ein Podcast abonniert werden soll, dessen Abo schon besteht, aber archiviert ist. */
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
 * Auswahl, woher ein Treffer kommt, den ARD Sounds und sein RSS-Feed beide liefern (Konzept 12.10): der Feed (wie in
 * anderen Apps, mit den Verzeichnissen, die ihn kennen) oder ARD Sounds (eigene Bilder und Folgenliste).
 */
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

/** Feld für die Feed-Adresse mit Hinweis bei `http://`, Fehlermeldung und „Abonnieren“. */
@Composable
private fun FeedUrlForm(url: String, onUrlChange: (String) -> Unit, state: AddPodcastState, onSubscribe: () -> Unit) {
    val working = state == AddPodcastState.Working
    Column(Modifier.fillMaxWidth().padding(horizontal = GroupMargin)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.podcast_add_url)) },
            placeholder = { Text(stringResource(R.string.podcast_add_url_hint)) },
            singleLine = true,
            enabled = !working,
            isError = state is AddPodcastState.Failed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) onSubscribe() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (isUnencrypted(url)) {
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
        SubscribeButton(working, enabled = url.isNotBlank(), onClick = onSubscribe, modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
    }
}

/** „Abonnieren“; während des Ladens mit Kreis statt Text. */
@Composable
private fun SubscribeButton(working: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = enabled && !working, modifier = modifier) {
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.podcast_adding), modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(stringResource(R.string.podcast_add_confirm))
        }
    }
}

/** Ein Treffer: Bild, Titel, Autor und die Verzeichnisse, die ihn kennen; abonnierte mit Häkchen. */
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
 * Vorschau eines Treffers (Konzept 12.10): lädt den Feed als Probeabo und übergibt dann an die normale Podcast-Seite
 * ([onOpened] mit der Id, auch wenn er schon abonniert ist). Bis dahin der Kopf mit den Angaben des Verzeichnisses.
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
            // Abbrechen führt zurück zu den Treffern.
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
