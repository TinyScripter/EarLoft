package keinabersmart.kleinaberlaut.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastSettings
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import keinabersmart.kleinaberlaut.ui.components.readableWidth
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.theme.ThemeMode
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Rahmen einer Unterseite der Einstellungen: Titelleiste mit Zurück, darunter die scrollbare Liste. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { BackButton(onBack) },
        )
        LazyColumn(Modifier.navigationBarsPadding(), content = content)
    }
}

/** Ganze Zeile schaltet (größere Tippfläche, ein Element für Screenreader). */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String,
    icon: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = if (enabled) {
            ListItemDefaults.colors()
        } else {
            ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
            )
        },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

private const val DISABLED_ALPHA = 0.38f

/** Zeile, die eine Auswahl-Dialog o. Ä. öffnet; [value] ist der aktuelle Wert. */
@Composable
internal fun ClickRow(title: String, value: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Auswahl eines Werts mit Radio-Zeilen; ein Tipp wählt und schließt. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    summary: @Composable (T) -> String? = { null },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    val optionSummary = summary(option)
                    ListItem(
                        headlineContent = { Text(label(option)) },
                        supportingContent = optionSummary?.let { { Text(it) } },
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

/** Der Wert wird erst beim Loslassen gespeichert. */
@Composable
internal fun PreAmpRow(preAmpDb: Float, enabled: Boolean, onPreAmpChange: (Float) -> Unit) {
    var value by remember(preAmpDb) { mutableFloatStateOf(preAmpDb) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_pre_amp)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_pre_amp_summary))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onPreAmpChange(value) },
                    valueRange = ReplayGainSettings.MIN_PRE_AMP_DB..ReplayGainSettings.MAX_PRE_AMP_DB,
                    steps = (ReplayGainSettings.MAX_PRE_AMP_DB - ReplayGainSettings.MIN_PRE_AMP_DB).toInt() - 1,
                    enabled = enabled,
                )
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_volume_up), contentDescription = null) },
        trailingContent = { Text(stringResource(R.string.settings_pre_amp_value, value)) },
    )
}

@Composable
internal fun ServerRow(
    item: ServerItem,
    onEdit: () -> Unit,
    onSyncNow: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val server = item.server
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.server_edit), onClick = onEdit),
        headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(server.username, server.baseUrl).joinToString(" @ "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val isError = item.passwordLost || item.error != null
                val status = when {
                    !server.enabled -> stringResource(R.string.server_disabled)
                    item.passwordLost -> stringResource(R.string.server_password_lost)
                    item.error != null -> stringResource(R.string.server_sync_failed, item.error)
                    server.lastFullSyncAt == 0L -> stringResource(R.string.server_never_synced)
                    else -> {
                        val ageMs = System.currentTimeMillis() - server.lastFullSyncAt
                        if (ageMs < MINUTE_MS) {
                            stringResource(R.string.server_synced_just_now)
                        } else {
                            stringResource(R.string.server_last_sync, relativeAge(ageMs))
                        }
                    }
                }
                Text(
                    status,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.autoDownload) {
                    Text(stringResource(R.string.server_auto_download), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
        trailingContent = {
            Row {
                if (server.enabled) {
                    IconButton(onClick = onSyncNow) {
                        Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.server_sync_now))
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = stringResource(R.string.server_remove))
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
                )
            }
        },
    )
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** „vor 5 Minuten“ – eigene Texte statt `DateUtils`, das der System-Sprache folgt. */
@Composable
private fun relativeAge(ageMs: Long): String = when {
    ageMs < HOUR_MS -> (ageMs / MINUTE_MS).toInt().let { pluralStringResource(R.plurals.age_minutes, it, it) }
    ageMs < DAY_MS -> (ageMs / HOUR_MS).toInt().let { pluralStringResource(R.plurals.age_hours, it, it) }
    else -> (ageMs / DAY_MS).toInt().let { pluralStringResource(R.plurals.age_days, it, it) }
}

@Composable
internal fun podcastRefreshLabel(hours: Int): String =
    if (hours == 0) {
        stringResource(R.string.settings_podcast_refresh_off)
    } else {
        pluralStringResource(R.plurals.settings_podcast_refresh_every, hours, hours)
    }

@Composable
internal fun podcastDeleteHeardLabel(days: Int): String = when (days) {
    PodcastSettings.DELETE_HEARD_NEVER -> stringResource(R.string.settings_podcast_delete_heard_never)
    PodcastSettings.DELETE_HEARD_IMMEDIATELY -> stringResource(R.string.settings_podcast_delete_heard_now)
    else -> pluralStringResource(R.plurals.settings_podcast_delete_heard_after, days, days)
}

@Composable
internal fun syncIntervalLabel(days: Int): String = stringResource(
    when (days) {
        LibrarySettings.SYNC_NEVER -> R.string.settings_sync_never
        1 -> R.string.settings_sync_daily
        7 -> R.string.settings_sync_weekly
        else -> R.string.settings_sync_monthly
    },
)

internal val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

internal val ReplayGainMode.label: Int
    get() = when (this) {
        ReplayGainMode.OFF -> R.string.replay_gain_off
        ReplayGainMode.TRACK -> R.string.replay_gain_track
        ReplayGainMode.ALBUM -> R.string.replay_gain_album
        ReplayGainMode.AUTO -> R.string.replay_gain_auto
    }
