package de.kleinabersmart.earloft.ui.settings

import androidx.compose.material3.SnackbarDuration
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import android.text.format.Formatter
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.core.wearsync.WearStatusDto
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun WatchSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val removed = stringResource(R.string.watch_removed)
    val undo = stringResource(R.string.action_undo)
    WatchSettingsContent(
        state = state,
        onBack = onBack,
        onQualityChange = viewModel::setQuality,
        onStorageLimitChange = viewModel::setStorageLimitGb,
        onRequireChargingChange = viewModel::setRequireCharging,
        // A single entry goes right away, with "Undo" like everywhere else; only "Remove everything" asks first.
        onRemove = { item ->
            viewModel.remove(item)
            snackbars.show(removed, SnackbarDuration.Long, undo) { viewModel.restore(item) }
        },
        onRemoveEpisode = { item ->
            viewModel.removeEpisode(item)
            snackbars.show(removed, SnackbarDuration.Long, undo) { viewModel.restoreEpisode(item) }
        },
        onClear = viewModel::clear,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchSettingsContent(
    state: WatchSettingsUiState,
    onBack: () -> Unit,
    onQualityChange: (WearQuality) -> Unit,
    onStorageLimitChange: (Int?) -> Unit,
    onRequireChargingChange: (Boolean) -> Unit,
    onRemove: (WatchSelectionItem) -> Unit,
    onRemoveEpisode: (WatchEpisodeItem) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingQuality by rememberSaveable { mutableStateOf(false) }
    var choosingLimit by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val settings = state.settings

    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.watch_settings_title)) },
            navigationIcon = { BackButton(onBack) },
        )
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            group("status-group") {
                row("status") { WatchStatusRows(state.status) }
            }
            group("selections", R.string.watch_selections) {
                val selections = state.selections
                if (selections != null && selections.isEmpty() && state.episodes.isEmpty()) {
                    row("empty") { EmptyHint(stringResource(R.string.watch_selections_empty)) }
                }
                selections.orEmpty().forEach { item ->
                    row(item.kind.key(item.refId)) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (item.kind == WearSelectionKind.FAVORITES) stringResource(R.string.favorites_title) else item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                val progress = state.status?.selections?.firstOrNull { it.key == item.kind.key(item.refId) }
                                if (progress != null && progress.done < progress.total) {
                                    Text(stringResource(R.string.watch_selection_progress, stringResource(item.kind.label), progress.done, progress.total))
                                } else {
                                    Text(pluralStringResource(R.plurals.watch_selection_songs, item.songCount, stringResource(item.kind.label), item.songCount))
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemove(item) }) {
                                    Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.watch_remove))
                                }
                            },
                        )
                    }
                }
                state.episodes.forEach { item ->
                    row("episode:${item.bookId}") {
                        ListItem(
                            headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    stringResource(if (item.isBook) R.string.watch_selection_book else R.string.watch_selection_episode, item.subtitle),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemoveEpisode(item) }) {
                                    Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.watch_remove))
                                }
                            },
                        )
                    }
                }
            }
            group("downloads", R.string.watch_downloads) {
                row("quality") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.watch_quality)) },
                        supportingContent = { Text(stringResource(settings.quality.label)) },
                        leadingContent = { Icon(painterResource(DesignR.drawable.ic_music_note), contentDescription = null) },
                        modifier = Modifier.clickable { choosingQuality = true },
                    )
                }
                row("limit") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.watch_storage_limit)) },
                        supportingContent = { Text(storageLimitLabel(settings.storageLimitGb)) },
                        leadingContent = { Icon(painterResource(DesignR.drawable.ic_download_done), contentDescription = null) },
                        modifier = Modifier.clickable { choosingLimit = true },
                    )
                }
                row("charging") {
                    SwitchRow(
                        title = stringResource(R.string.watch_require_charging),
                        summary = stringResource(R.string.watch_require_charging_summary),
                        icon = DesignR.drawable.ic_download,
                        checked = settings.requireCharging,
                        onCheckedChange = onRequireChargingChange,
                    )
                }
                row("clear") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.watch_clear), color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                        modifier = Modifier.clickable { confirmClear = true },
                    )
                }
            }
        }
    }

    if (choosingQuality) {
        ChoiceDialog(
            title = stringResource(R.string.watch_quality),
            options = WearQuality.entries,
            selected = settings.quality,
            label = { stringResource(it.label) },
            onSelect = {
                onQualityChange(it)
                choosingQuality = false
            },
            onDismiss = { choosingQuality = false },
        )
    }
    if (choosingLimit) {
        ChoiceDialog(
            title = stringResource(R.string.watch_storage_limit),
            options = STORAGE_LIMITS_GB,
            selected = settings.storageLimitGb,
            label = { storageLimitLabel(it) },
            onSelect = {
                onStorageLimitChange(it)
                choosingLimit = false
            },
            onDismiss = { choosingLimit = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.watch_clear)) },
            text = { Text(stringResource(R.string.watch_clear_text)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The watch's storage and the notes from its last report. */
@Composable
private fun WatchStatusRows(status: WearStatusDto?) {
    val context = LocalContext.current
    Column {
        if (status == null) {
            ListItem(headlineContent = { Text(stringResource(R.string.watch_status_none)) })
            return
        }
        ListItem(
            headlineContent = {
                Text(
                    stringResource(
                        R.string.watch_status_storage,
                        Formatter.formatFileSize(context, status.usedBytes),
                        Formatter.formatFileSize(context, status.freeBytes),
                    ),
                )
            },
            supportingContent = status.limitBytes?.let { limit ->
                { Text(stringResource(R.string.watch_status_limit, Formatter.formatFileSize(context, limit))) }
            },
        )
        if (status.missingForStorage > 0) {
            ListItem(
                headlineContent = {
                    Text(
                        pluralStringResource(R.plurals.watch_status_missing, status.missingForStorage, status.missingForStorage),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        // In the phone's language if the watch sends the code; from an older watch its text (review2 Z20).
        val error = WearDownloadError.of(status.lastErrorCode)?.let { stringResource(it.textRes) } ?: status.lastError
        error?.let {
            ListItem(headlineContent = { Text(stringResource(R.string.watch_status_error, it)) })
        }
    }
}

/** `null` = automatic (W1). */
private val STORAGE_LIMITS_GB = listOf(null, 2, 4, 8, 16, 32)

@Composable
private fun storageLimitLabel(gigabytes: Int?): String =
    if (gigabytes == null) stringResource(R.string.watch_storage_auto) else stringResource(R.string.watch_storage_gb, gigabytes)

internal val WearQuality.label: Int
    get() = when (this) {
        WearQuality.OPUS_128 -> R.string.watch_quality_opus
        WearQuality.ORIGINAL -> R.string.watch_quality_original
    }

private val WearSelectionKind.label: Int
    get() = when (this) {
        WearSelectionKind.ALBUM -> R.string.watch_selection_album
        WearSelectionKind.PLAYLIST -> R.string.watch_selection_playlist
        WearSelectionKind.FAVORITES -> R.string.watch_selection_favorites
    }

@Preview(showBackground = true)
@Composable
private fun WatchSettingsContentPreview() {
    EarLoftTheme {
        WatchSettingsContent(
            state = WatchSettingsUiState(
                selections = listOf(
                    WatchSelectionItem(WearSelectionKind.FAVORITES, null, "", 42),
                    WatchSelectionItem(WearSelectionKind.ALBUM, 1, "Moonlight", 4),
                ),
                episodes = listOf(WatchEpisodeItem(7, "Episode 42: Watches", "Small Podcast")),
            ),
            onBack = {},
            onQualityChange = {},
            onStorageLimitChange = {},
            onRequireChargingChange = {},
            onRemove = {},
            onRemoveEpisode = {},
            onClear = {},
        )
    }
}

/** The watch's error as the phone words it. */
private val WearDownloadError.textRes: Int
    get() = when (this) {
        WearDownloadError.SERVER_VIA_PHONE -> R.string.watch_error_server_via_phone
        WearDownloadError.SERVER_REFUSED_VIA_PHONE -> R.string.watch_error_server_refused_via_phone
        WearDownloadError.SERVER_NO_PHONE_COPY -> R.string.watch_error_server_no_phone_copy
        WearDownloadError.PHONE_NO_COPY -> R.string.watch_error_phone_no_copy
        WearDownloadError.STORAGE_FULL -> R.string.watch_error_storage_full
        WearDownloadError.PHONE_UNREACHABLE -> R.string.watch_error_phone_unreachable
    }
