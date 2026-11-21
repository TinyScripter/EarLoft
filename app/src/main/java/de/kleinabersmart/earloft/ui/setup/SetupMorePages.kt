package de.kleinabersmart.earloft.ui.setup

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import android.os.Build
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.settings.SettingsUiState
import de.kleinabersmart.earloft.ui.settings.SwitchRow
import de.kleinabersmart.earloft.ui.settings.label
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import de.kleinabersmart.earloft.wear.WatchSettings
import de.kleinabersmart.earloft.wear.WatchSetup
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** What the watch and appearance pages toggle. */
internal class SetupMoreActions(
    val setWatchQuality: (WearQuality) -> Unit,
    val setWatchRequireCharging: (Boolean) -> Unit,
    val setThemeMode: (ThemeMode) -> Unit,
    val setSystemColors: (Boolean) -> Unit,
    val setPlayerCoverColors: (Boolean) -> Unit,
)

/**
 * The watch, only if there is one: with EarLoft on it, the most important settings of the watch page; otherwise the hint
 * to install the watch app (there is no store listing yet).
 */
internal fun LazyListScope.watchPage(watch: WatchSetup, settings: WatchSettings, actions: SetupMoreActions) {
    if (watch != WatchSetup.APP_INSTALLED) {
        pageHeader(R.string.settings_watch, R.string.setup_watch_missing_text, icon = DesignR.drawable.ic_watch)
        return
    }
    pageHeader(R.string.settings_watch, R.string.setup_watch_text, icon = DesignR.drawable.ic_watch)
    group("quality", R.string.watch_quality) {
        // One row, so screen readers announce the choices as one group ("1 of 2").
        row("quality-choices") {
            Column(Modifier.selectableGroup()) {
                WearQuality.entries.forEach { quality ->
                    RadioRow(stringResource(quality.label), quality == settings.quality) { actions.setWatchQuality(quality) }
                }
            }
        }
    }
    group("downloads", R.string.watch_downloads) {
        row("charging") {
            SwitchRow(
                title = stringResource(R.string.watch_require_charging),
                summary = stringResource(R.string.watch_require_charging_summary),
                icon = DesignR.drawable.ic_download,
                checked = settings.requireCharging,
                onCheckedChange = actions.setWatchRequireCharging,
            )
        }
    }
    pageHint("watch-hint", R.string.setup_watch_hint)
}

/** Light, dark or follow the system, plus the colors; takes effect immediately, including on the wizard. */
internal fun LazyListScope.designPage(state: SettingsUiState, actions: SetupMoreActions) {
    pageHeader(R.string.settings_section_appearance, R.string.setup_design_text, icon = DesignR.drawable.ic_palette)
    group("theme", R.string.settings_theme) {
        // One row, so screen readers announce the choices as one group ("1 of 3").
        row("theme-choices") {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(stringResource(mode.label), mode == state.themeMode) { actions.setThemeMode(mode) }
                }
            }
        }
    }
    group("colors", R.string.setup_section_colors) {
        // Material You only exists from Android 12; before that, the app colors always apply.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            row("system-colors") {
                SwitchRow(
                    title = stringResource(R.string.settings_system_colors),
                    summary = stringResource(R.string.settings_system_colors_summary),
                    icon = DesignR.drawable.ic_palette,
                    checked = state.systemColors,
                    onCheckedChange = actions.setSystemColors,
                )
            }
        }
        row("player-cover-colors") {
            SwitchRow(
                title = stringResource(R.string.settings_player_cover_colors),
                summary = stringResource(R.string.settings_player_cover_colors_summary),
                icon = DesignR.drawable.ic_play,
                checked = state.playerCoverColors,
                onCheckedChange = actions.setPlayerCoverColors,
            )
        }
    }
}

/** A choice among a few; the whole row selects (one element for screen readers). */
@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}
