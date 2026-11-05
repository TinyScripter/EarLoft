package de.kleinabersmart.kleinaberlaut.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.data.wear.WatchSpoken
import de.kleinabersmart.kleinaberlaut.core.data.wear.WearSelectionRepository
import de.kleinabersmart.kleinaberlaut.core.model.WearSelectionKind
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearSelectionStatusDto
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearStatusDto
import de.kleinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import de.kleinabersmart.kleinaberlaut.wear.WatchConnection
import de.kleinabersmart.kleinaberlaut.wear.WatchStatusRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Was sich einzeln auf die Uhr legen lässt: Album, Playlist, Favoriten (Wear-Konzept 5) oder eine Folge bzw. ein Buch (16.1/16.2). */
sealed interface WatchTarget {
    data class Selection(val kind: WearSelectionKind, val refId: Long?) : WatchTarget
    data class Spoken(val bookId: Long) : WatchTarget
}

/** Was auf die Uhr soll (Wear-Konzept 5). Ohne gekoppelte Uhr mit kleinaberlaut ist [isAvailable] `false`. */
@Stable
class WatchController(
    private val available: State<Boolean>,
    private val selectedKeys: State<Set<String>>,
    private val selectedSpoken: State<Set<Long>>,
    private val status: State<WearStatusDto?>,
    val setSelected: (target: WatchTarget, selected: Boolean) -> Unit,
) {
    val isAvailable: Boolean get() = available.value

    fun isSelected(kind: WearSelectionKind, refId: Long?): Boolean = kind.key(refId) in selectedKeys.value

    fun isSelected(target: WatchTarget): Boolean = when (target) {
        is WatchTarget.Selection -> isSelected(target.kind, target.refId)
        is WatchTarget.Spoken -> target.bookId in selectedSpoken.value
    }

    /** Fortschritt der Uhr für diese Auswahl; `null`, wenn sie nicht ausgewählt ist oder die Uhr noch nichts gemeldet hat. */
    fun progressOf(kind: WearSelectionKind, refId: Long?): WearSelectionStatusDto? {
        val key = kind.key(refId)
        if (key !in selectedKeys.value) return null
        return status.value?.selections?.firstOrNull { it.key == key }
    }

    /** Titel, die die Uhr wegen der Speichergrenze nicht eingereiht hat. */
    val missingForStorage: Int get() = status.value?.missingForStorage ?: 0
}

/** `null` (Vorschau, Tests): keine Uhr-Funktionen. */
val LocalWatchController = staticCompositionLocalOf<WatchController?> { null }

@HiltViewModel
class WatchViewModel @Inject constructor(
    watchConnection: WatchConnection,
    private val selections: WearSelectionRepository,
    private val spoken: WatchSpoken,
    watchStatus: WatchStatusRepository,
) : ViewModel() {
    val status: StateFlow<WearStatusDto?> = watchStatus.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val available: StateFlow<Boolean> = watchConnection.watchAppInstalled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val selectedKeys: StateFlow<Set<String>> = selections.selectedKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val selectedSpoken: StateFlow<Set<Long>> = spoken.selectedIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setSelected(target: WatchTarget, selected: Boolean) {
        viewModelScope.launch {
            when (target) {
                is WatchTarget.Selection -> selections.setSelected(target.kind, target.refId, selected)
                is WatchTarget.Spoken -> spoken.setSelected(target.bookId, selected)
            }
        }
    }
}

@Composable
fun rememberWatchController(viewModel: WatchViewModel = hiltViewModel()): WatchController {
    val available = viewModel.available.collectAsStateWithLifecycle()
    val selectedKeys = viewModel.selectedKeys.collectAsStateWithLifecycle()
    val selectedSpoken = viewModel.selectedSpoken.collectAsStateWithLifecycle()
    val status = viewModel.status.collectAsStateWithLifecycle()
    return remember(viewModel) { WatchController(available, selectedKeys, selectedSpoken, status, viewModel::setSelected) }
}

/** Kleine Zeile „Auf der Uhr: 12 von 14“ für die Kopfzeile von Album, Playlist und Favoriten; ohne Auswahl leer. */
@Composable
fun WatchStatusText(kind: WearSelectionKind, refId: Long?, modifier: Modifier = Modifier) {
    val controller = LocalWatchController.current ?: return
    if (!controller.isAvailable) return
    val progress = controller.progressOf(kind, refId) ?: return
    val text = when {
        progress.failed > 0 -> stringResource(R.string.watch_status_failed, progress.done, progress.total, progress.failed)
        progress.done < progress.total && controller.missingForStorage > 0 -> stringResource(R.string.watch_status_full)
        progress.done < progress.total -> stringResource(R.string.watch_status_progress, progress.done, progress.total)
        else -> stringResource(R.string.watch_status_done, progress.total)
    }
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Knopf „Auf Uhr laden“ / „Von Uhr entfernen“ für die TopAppBar; ohne Uhr unsichtbar. */
@Composable
fun WatchToggleButton(kind: WearSelectionKind, refId: Long?) = WatchToggleButton(WatchTarget.Selection(kind, refId))

@Composable
fun WatchToggleButton(target: WatchTarget) {
    val controller = LocalWatchController.current ?: return
    if (!controller.isAvailable) return
    val selected = controller.isSelected(target)
    val onToggle = watchToggle(controller, target)
    val icon: @Composable () -> Unit = {
        Icon(
            painterResource(DesignR.drawable.ic_watch),
            contentDescription = stringResource(if (selected) R.string.watch_remove else R.string.watch_add),
        )
    }
    if (selected) {
        FilledTonalIconToggleButton(checked = true, onCheckedChange = onToggle, content = icon)
    } else {
        IconToggleButton(checked = false, onCheckedChange = onToggle, content = icon)
    }
}

/**
 * Eintrag „Auf Uhr laden“ / „Von Uhr entfernen“ für die Kontextmenüs (Album, Folge), direkt über „Download entfernen“;
 * ohne Uhr unsichtbar. [withIcon] wie die übrigen Einträge des Menüs.
 */
@Composable
fun WatchMenuItem(target: WatchTarget, onDismiss: () -> Unit, withIcon: Boolean = true) {
    val controller = LocalWatchController.current ?: return
    if (!controller.isAvailable) return
    val selected = controller.isSelected(target)
    val onToggle = watchToggle(controller, target)
    DropdownMenuItem(
        text = { Text(stringResource(if (selected) R.string.watch_remove else R.string.watch_add)) },
        leadingIcon = if (withIcon) {
            { Icon(painterResource(DesignR.drawable.ic_watch), contentDescription = null) }
        } else {
            null
        },
        onClick = {
            onToggle(!selected)
            onDismiss()
        },
    )
}

@Composable
private fun watchToggle(controller: WatchController, target: WatchTarget): (Boolean) -> Unit {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    return { select ->
        controller.setSelected(target, select)
        snackbars.show(resources.getString(if (select) R.string.watch_added else R.string.watch_removed))
    }
}
