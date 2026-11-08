package de.kleinabersmart.earloft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.data.spoken.ResumeRule
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.data.sync.SyncFailure
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import de.kleinabersmart.earloft.core.playback.ReplayGainSettings
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

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
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp), content = content)
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
    // Auf schmalen Handys stehen die Knöpfe unter dem Text, sonst bleibt für Name und Adresse kaum Breite.
    val narrow = LocalConfiguration.current.screenWidthDp < 400
    val actions: @Composable () -> Unit = {
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
    }
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
                    item.error != null -> stringResource(R.string.server_sync_failed, syncFailureText(item.error, server.type))
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
                if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
        trailingContent = if (narrow) null else actions,
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

/** „Kein Server dieser Art“ – im Server-Formular und in der Serverliste. */
@StringRes
internal fun notServerText(type: SourceType): Int = when (type) {
    SourceType.JELLYFIN -> R.string.add_server_not_jellyfin
    SourceType.EMBY -> R.string.add_server_not_emby
    else -> R.string.add_server_not_subsonic
}

/** Dieselben Texte wie beim Verbindungstest im Server-Formular. */
@Composable
private fun syncFailureText(failure: SyncFailure, type: SourceType): String = when (failure) {
    SyncFailure.WrongCredentials -> stringResource(R.string.add_server_wrong_credentials)
    SyncFailure.UnexpectedServer -> stringResource(notServerText(type))
    is SyncFailure.Unreachable -> stringResource(R.string.add_server_unreachable, failure.reason.orEmpty())
    is SyncFailure.ServerError -> stringResource(R.string.add_server_server_error, failure.code, failure.message.orEmpty())
    is SyncFailure.Other -> failure.message
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

/** „30 s“, „5 min“, „1 h“ – für Pausen- und Rückspulzeiten. */
@Composable
internal fun resumeSecondsLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.settings_resume_rewind_off)
    seconds % 3600 == 0 -> stringResource(R.string.settings_resume_hours, seconds / 3600)
    seconds % 60 == 0 -> stringResource(R.string.settings_resume_minutes, seconds / 60)
    else -> stringResource(R.string.settings_resume_seconds, seconds)
}

/** Die zwei Zeilen „Zurückspulen nach Pause von …“ und „Zurückspulen um …“ samt Auswahldialogen. */
@Composable
internal fun ResumeRows(rule: ResumeRule, icon: Int, keyPrefix: String, onChange: (ResumeRule) -> Unit) {
    var choosingAfter by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    var choosingRewind by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    ClickRow(
        title = stringResource(R.string.settings_resume_after),
        value = resumeSecondsLabel(rule.afterSeconds),
        icon = icon,
        onClick = { choosingAfter = true },
    )
    ClickRow(
        title = stringResource(R.string.settings_resume_rewind),
        value = resumeSecondsLabel(rule.rewindSeconds),
        icon = icon,
        onClick = { choosingRewind = true },
    )
    if (choosingAfter) {
        ChoiceDialog(
            title = stringResource(R.string.settings_resume_after),
            options = (ResumeRule.AFTER_CHOICES + rule.afterSeconds).distinct().sorted(),
            selected = rule.afterSeconds,
            label = { resumeSecondsLabel(it) },
            onSelect = {
                onChange(rule.copy(afterSeconds = it))
                choosingAfter = false
            },
            onDismiss = { choosingAfter = false },
        )
    }
    if (choosingRewind) {
        ChoiceDialog(
            title = stringResource(R.string.settings_resume_rewind),
            options = (ResumeRule.REWIND_CHOICES + rule.rewindSeconds).distinct().sorted(),
            selected = rule.rewindSeconds,
            label = { resumeSecondsLabel(it) },
            onSelect = {
                onChange(rule.copy(rewindSeconds = it))
                choosingRewind = false
            },
            onDismiss = { choosingRewind = false },
        )
    }
}

/** Die zwei Zeilen „Zurückspulen per Knopf“ und „Vorspulen per Knopf“ samt Auswahldialogen. */
@Composable
internal fun SeekRows(steps: SeekSteps, keyPrefix: String, onChange: (SeekSteps) -> Unit) {
    var choosingBack by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    var choosingForward by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    ClickRow(
        title = stringResource(R.string.settings_seek_back),
        value = resumeSecondsLabel(steps.backSeconds),
        icon = DesignR.drawable.ic_replay,
        onClick = { choosingBack = true },
    )
    ClickRow(
        title = stringResource(R.string.settings_seek_forward),
        value = resumeSecondsLabel(steps.forwardSeconds),
        icon = DesignR.drawable.ic_forward,
        onClick = { choosingForward = true },
    )
    if (choosingBack) {
        ChoiceDialog(
            title = stringResource(R.string.settings_seek_back),
            options = (SeekSteps.CHOICES + steps.backSeconds).distinct().sorted(),
            selected = steps.backSeconds,
            label = { resumeSecondsLabel(it) },
            onSelect = {
                onChange(steps.copy(backSeconds = it))
                choosingBack = false
            },
            onDismiss = { choosingBack = false },
        )
    }
    if (choosingForward) {
        ChoiceDialog(
            title = stringResource(R.string.settings_seek_forward),
            options = (SeekSteps.CHOICES + steps.forwardSeconds).distinct().sorted(),
            selected = steps.forwardSeconds,
            label = { resumeSecondsLabel(it) },
            onSelect = {
                onChange(steps.copy(forwardSeconds = it))
                choosingForward = false
            },
            onDismiss = { choosingForward = false },
        )
    }
}

/** Name eines Bereichs wie in der Übersicht der Einstellungen. */
internal fun areaTitle(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> R.string.settings_music
    SpokenMode.PODCASTS -> R.string.settings_podcasts
    SpokenMode.BOOKS -> R.string.settings_audiobooks
}

/** Woher die Inhalte eines Bereichs kommen (Allgemein → Bereiche). */
internal fun areaSummary(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> R.string.settings_area_music_summary
    SpokenMode.PODCASTS -> R.string.settings_area_podcasts_summary
    SpokenMode.BOOKS -> R.string.settings_area_books_summary
}

/** Symbol eines Bereichs wie in der Übersicht der Einstellungen. */
internal fun areaIcon(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> DesignR.drawable.ic_music_note
    SpokenMode.PODCASTS -> DesignR.drawable.ic_playlist_play
    SpokenMode.BOOKS -> DesignR.drawable.ic_book
}
