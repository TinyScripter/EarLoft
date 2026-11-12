package de.kleinabersmart.earloft.ui.setup

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

/** Was die Seiten Uhr und Darstellung schalten. */
internal class SetupMoreActions(
    val setWatchQuality: (WearQuality) -> Unit,
    val setWatchRequireCharging: (Boolean) -> Unit,
    val setThemeMode: (ThemeMode) -> Unit,
    val setSystemColors: (Boolean) -> Unit,
    val setPlayerCoverColors: (Boolean) -> Unit,
    val setWidgetCoverColors: (Boolean) -> Unit,
)

/**
 * Die Uhr, nur wenn es eine gibt: mit EarLoft darauf die wichtigsten Einstellungen der Uhr-Seite, sonst der Hinweis, die
 * Uhr-App zu installieren (einen Store-Eintrag gibt es noch nicht).
 */
internal fun LazyListScope.watchPage(watch: WatchSetup, settings: WatchSettings, actions: SetupMoreActions) {
    if (watch != WatchSetup.APP_INSTALLED) {
        pageHeader(R.string.settings_watch, R.string.setup_watch_missing_text, icon = DesignR.drawable.ic_watch)
        return
    }
    pageHeader(R.string.settings_watch, R.string.setup_watch_text, icon = DesignR.drawable.ic_watch)
    group("quality", R.string.watch_quality) {
        WearQuality.entries.forEach { quality ->
            row("quality-${quality.name}") {
                RadioRow(stringResource(quality.label), quality == settings.quality) { actions.setWatchQuality(quality) }
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

/** Hell, dunkel oder wie das System, dazu die Farben; wirkt sofort, auch auf den Assistenten. */
internal fun LazyListScope.designPage(state: SettingsUiState, actions: SetupMoreActions) {
    pageHeader(R.string.settings_section_appearance, R.string.setup_design_text, icon = DesignR.drawable.ic_palette)
    group("theme", R.string.settings_theme) {
        ThemeMode.entries.forEach { mode ->
            row("theme-${mode.name}") {
                RadioRow(stringResource(mode.label), mode == state.themeMode) { actions.setThemeMode(mode) }
            }
        }
    }
    group("colors", R.string.setup_section_colors) {
        // Material You gibt es erst ab Android 12; davor gelten immer die App-Farben.
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
        row("widget-cover-colors") {
            SwitchRow(
                title = stringResource(R.string.settings_widget_cover_colors),
                summary = stringResource(R.string.settings_widget_cover_colors_summary),
                icon = DesignR.drawable.ic_music_note,
                checked = state.widgetCoverColors,
                onCheckedChange = actions.setWidgetCoverColors,
            )
        }
    }
}

/** Eine Wahl aus wenigen; die ganze Zeile wählt (ein Element für Screenreader). */
@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}
