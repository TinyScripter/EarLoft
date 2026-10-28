package keinabersmart.kleinaberlaut.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.wear.WearSelectionRepository
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.wear.WatchSettings
import keinabersmart.kleinaberlaut.wear.WatchSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

data class WatchSelectionItem(
    val kind: WearSelectionKind,
    val refId: Long?,
    val title: String,
    val songCount: Int,
)

data class WatchSettingsUiState(
    val settings: WatchSettings = WatchSettings(),
    /** `null` = wird geladen. */
    val selections: List<WatchSelectionItem>? = null,
)

@HiltViewModel
class WatchSettingsViewModel @Inject constructor(
    private val watchSettings: WatchSettingsRepository,
    private val selections: WearSelectionRepository,
) : ViewModel() {

    val state: StateFlow<WatchSettingsUiState> = combine(watchSettings.settings, selections.content()) { settings, content ->
        WatchSettingsUiState(
            settings = settings,
            selections = content.selections.map { WatchSelectionItem(it.kind, it.refId, it.title, it.songIds.size) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettingsUiState())

    fun setQuality(quality: WearQuality) = launch { watchSettings.setQuality(quality) }

    fun setStorageLimitGb(gigabytes: Int?) = launch { watchSettings.setStorageLimitGb(gigabytes) }

    fun setRequireCharging(requireCharging: Boolean) = launch { watchSettings.setRequireCharging(requireCharging) }

    fun remove(item: WatchSelectionItem) = launch { selections.setSelected(item.kind, item.refId, selected = false) }

    fun clear() = launch { selections.clear() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun WatchSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WatchSettingsContent(
        state = state,
        onBack = onBack,
        onQualityChange = viewModel::setQuality,
        onStorageLimitChange = viewModel::setStorageLimitGb,
        onRequireChargingChange = viewModel::setRequireCharging,
        onRemove = viewModel::remove,
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
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingQuality by rememberSaveable { mutableStateOf(false) }
    var choosingLimit by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val settings = state.settings

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.watch_settings_title)) },
            navigationIcon = { BackButton(onBack) },
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item(key = "selections") { SectionHeader(stringResource(R.string.watch_selections)) }
            val selections = state.selections
            if (selections != null && selections.isEmpty()) {
                item(key = "empty") { EmptyHint(stringResource(R.string.watch_selections_empty)) }
            }
            items(selections.orEmpty(), key = { it.kind.key(it.refId) }) { item ->
                ListItem(
                    headlineContent = {
                        Text(
                            if (item.kind == WearSelectionKind.FAVORITES) stringResource(R.string.favorites_title) else item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.watch_selection_songs, item.songCount, stringResource(item.kind.label), item.songCount))
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemove(item) }) {
                            Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.watch_remove))
                        }
                    },
                )
            }
            item(key = "downloads") { SectionHeader(stringResource(R.string.watch_downloads)) }
            item(key = "quality") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.watch_quality)) },
                    supportingContent = { Text(stringResource(settings.quality.label)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_music_note), contentDescription = null) },
                    modifier = Modifier.clickable { choosingQuality = true },
                )
            }
            item(key = "limit") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.watch_storage_limit)) },
                    supportingContent = { Text(storageLimitLabel(settings.storageLimitGb)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download_done), contentDescription = null) },
                    modifier = Modifier.clickable { choosingLimit = true },
                )
            }
            item(key = "charging") {
                SwitchRow(
                    title = stringResource(R.string.watch_require_charging),
                    summary = stringResource(R.string.watch_require_charging_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = settings.requireCharging,
                    onCheckedChange = onRequireChargingChange,
                )
            }
            item(key = "clear") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.watch_clear), color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                    modifier = Modifier.clickable { confirmClear = true },
                )
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
                TextButton(onClick = {
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

/** `null` = automatisch (W1). */
private val STORAGE_LIMITS_GB = listOf(null, 2, 4, 8, 16, 32)

@Composable
private fun storageLimitLabel(gigabytes: Int?): String =
    if (gigabytes == null) stringResource(R.string.watch_storage_auto) else stringResource(R.string.watch_storage_gb, gigabytes)

private val WearQuality.label: Int
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

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(label(option)) },
                        leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun WatchSettingsContentPreview() {
    KleinaberlautTheme {
        WatchSettingsContent(
            state = WatchSettingsUiState(
                selections = listOf(
                    WatchSelectionItem(WearSelectionKind.FAVORITES, null, "", 42),
                    WatchSelectionItem(WearSelectionKind.ALBUM, 1, "Mondlicht", 4),
                ),
            ),
            onBack = {},
            onQualityChange = {},
            onStorageLimitChange = {},
            onRequireChargingChange = {},
            onRemove = {},
            onClear = {},
        )
    }
}
