package de.kleinabersmart.earloft.ui.settings

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import de.kleinabersmart.earloft.core.model.SpokenServer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.ui.spoken.volumeBoostLabel
import de.kleinabersmart.earloft.core.data.spoken.ResumeRule
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.data.sync.SyncFailure
import de.kleinabersmart.earloft.core.model.MobileStreamQuality
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import de.kleinabersmart.earloft.core.playback.ReplayGainSettings
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Frame of a settings subpage: title bar with Back, below it the scrollable list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { BackButton(onBack) },
        )
        LazyColumn(Modifier.navigationBarsPadding(), state = listState, contentPadding = PaddingValues(bottom = 16.dp), content = content)
    }
}

/** The whole row toggles (larger touch target, one element for screen readers). */
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

/** Row that opens a selection dialog or similar; [value] is the current value. */
@Composable
internal fun ClickRow(title: String, value: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Selection of a value with radio rows; a tap selects and closes. [description] appears once above the rows, [footer]
 * below them (e.g. a slider for a custom value).
 */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    summary: @Composable (T) -> String? = { null },
    description: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                description?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium) }
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
                footer?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The value is only saved on release. */
@Composable
internal fun PreAmpRow(preAmpDb: Float, enabled: Boolean, onPreAmpChange: (Float) -> Unit) {
    var value by remember(preAmpDb) { mutableFloatStateOf(preAmpDb) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_pre_amp)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_pre_amp_summary))
                val shown = stringResource(R.string.settings_pre_amp_value, value)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onPreAmpChange(value) },
                    // TalkBack reads the decibels, not a percentage of the range (review2 J97).
                    modifier = Modifier.semantics { stateDescription = shown },
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

/**
 * Sync and on/off of a server row (music and Audiobookshelf alike). Removing is in the server's edit form, not next to the
 * switch where it was easy to hit by mistake. The switch carries the server's name for TalkBack.
 */
@Composable
private fun ServerRowActions(name: String, enabled: Boolean, syncLabel: String, onSyncNow: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    Row {
        if (enabled) {
            IconButton(onClick = onSyncNow) {
                // With the server's name, like the switch: several rows otherwise all read "Sync now" (review2 J97).
                Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.action_for_item, syncLabel, name))
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp).semantics { contentDescription = name },
        )
    }
}

/** On narrow phones the buttons sit below the text, otherwise there's hardly any width left for name and address. */
@Composable
private fun narrowServerRow(): Boolean = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 400.dp

@Composable
internal fun ServerRow(
    item: ServerItem,
    onEdit: () -> Unit,
    onSyncNow: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val server = item.server
    val narrow = narrowServerRow()
    val actions: @Composable () -> Unit = {
        ServerRowActions(server.name, server.enabled, stringResource(R.string.server_sync_now), onSyncNow, onEnabledChange)
    }
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.server_edit), onClick = onEdit),
        headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val account = server.username
                val address = server.baseUrl
                Text(
                    if (account != null) stringResource(R.string.server_account_at_address, account, address) else address,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A switched-off server is not an error, as in the book server rows (review2 J64).
                val isError = server.enabled && (item.passwordLost || item.error != null)
                val status = when {
                    !server.enabled -> stringResource(R.string.server_disabled)
                    item.passwordLost -> stringResource(R.string.server_password_lost)
                    item.error != null -> stringResource(R.string.server_sync_failed, syncFailureText(item.error, server.type))
                    // 1: after a migration the full sync is due (`EarLoftDatabase.SeveralGenres`), not a point in time.
                    server.lastFullSyncAt <= 1L -> stringResource(R.string.server_never_synced)
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

/**
 * An Audiobookshelf server, built like [ServerRow]: name, address, state (off, key or login lost, last sync), sync and switch;
 * with several book libraries their checkboxes below. A tap opens the edit form.
 */
@Composable
internal fun AbsServerRow(
    server: SpokenServer,
    keyLost: Boolean,
    failure: SyncFailure?,
    onEdit: () -> Unit,
    onSyncNow: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onLibraryShown: (libraryId: String, shown: Boolean) -> Unit,
) {
    val narrow = narrowServerRow()
    val actions: @Composable () -> Unit = {
        ServerRowActions(server.name, server.enabled, stringResource(R.string.spoken_abs_sync), onSyncNow, onEnabledChange)
    }
    Column {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = stringResource(R.string.server_edit), onClick = onEdit),
            headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(server.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        !server.enabled -> stringResource(R.string.server_disabled)
                        keyLost -> stringResource(if (server.openId) R.string.spoken_abs_login_lost else R.string.spoken_abs_key_lost)
                        // The sync error as with music servers (review H39); a rejected key or sign-in says what to renew.
                        failure == SyncFailure.WrongCredentials ->
                            stringResource(if (server.openId) R.string.spoken_abs_login_lost else R.string.spoken_abs_error_auth)
                        failure != null -> stringResource(R.string.server_sync_failed, syncFailureText(failure, type = null))
                        server.lastFullSyncAt <= 0L -> stringResource(R.string.server_never_synced)
                        else -> {
                            val ageMs = System.currentTimeMillis() - server.lastFullSyncAt
                            if (ageMs < MINUTE_MS) stringResource(R.string.server_synced_just_now) else stringResource(R.string.server_last_sync, relativeAge(ageMs))
                        }
                    }
                    Text(
                        status,
                        color = if ((keyLost || failure != null) && server.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (narrow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                }
            },
            leadingContent = { Icon(painterResource(DesignR.drawable.ic_book), contentDescription = null) },
            trailingContent = if (narrow) null else actions,
        )
        // Several book libraries: each can be deselected (books_improvements M3); the last one shown stays.
        if (server.libraries.size > 1) {
            Text(
                stringResource(R.string.spoken_abs_libraries),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, top = 4.dp),
            )
            val shownCount = server.libraries.count { it.shown }
            server.libraries.forEach { library ->
                val locked = library.shown && shownCount == 1
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = library.shown,
                            enabled = server.enabled && !locked,
                            role = Role.Checkbox,
                            onValueChange = { onLibraryShown(library.libraryId, it) },
                        )
                        .padding(start = 44.dp, end = 16.dp),
                ) {
                    Checkbox(checked = library.shown, onCheckedChange = null, enabled = server.enabled && !locked)
                    Text(library.name, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** "5 minutes ago" – our own texts instead of `DateUtils`, which follows the system language. */
@Composable
private fun relativeAge(ageMs: Long): String = when {
    ageMs < HOUR_MS -> (ageMs / MINUTE_MS).toInt().let { pluralStringResource(R.plurals.age_minutes, it, it) }
    ageMs < DAY_MS -> (ageMs / HOUR_MS).toInt().let { pluralStringResource(R.plurals.age_hours, it, it) }
    else -> (ageMs / DAY_MS).toInt().let { pluralStringResource(R.plurals.age_days, it, it) }
}

/** "No server of this type" – in the server form and in the server list. */
@StringRes
internal fun notServerText(type: SourceType): Int = when (type) {
    SourceType.JELLYFIN -> R.string.add_server_not_jellyfin
    SourceType.EMBY -> R.string.add_server_not_emby
    else -> R.string.add_server_not_subsonic
}

/** "Server unreachable", with the reason in brackets only if there is one (review4 T34: otherwise "()"). */
@Composable
internal fun unreachableText(reason: String?): String =
    if (reason.isNullOrBlank()) stringResource(R.string.add_server_unreachable_plain) else stringResource(R.string.add_server_unreachable, reason)

/** The same texts as for the connection test in the server form. */
@Composable
/** [type] `null`: an Audiobookshelf server. */
private fun syncFailureText(failure: SyncFailure, type: SourceType?): String = when (failure) {
    SyncFailure.WrongCredentials -> stringResource(R.string.add_server_wrong_credentials)
    SyncFailure.UnexpectedServer -> stringResource(if (type == null) R.string.spoken_abs_error_unreachable else notServerText(type))
    is SyncFailure.Unreachable -> unreachableText(failure.reason)
    is SyncFailure.ServerError -> stringResource(R.string.add_server_server_error, failure.code, failure.message.orEmpty())
    is SyncFailure.LibraryChanged -> stringResource(R.string.sync_failed_library_changed, failure.libraryName)
    SyncFailure.PermissionMissing -> stringResource(R.string.sync_failed_permission)
    SyncFailure.LocalUnavailable -> stringResource(R.string.sync_failed_local_unavailable)
    // The exception text is technical and English; it is in the log.
    is SyncFailure.Other -> stringResource(R.string.sync_failed_other)
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

internal val MobileStreamQuality.label: Int
    get() = when (this) {
        MobileStreamQuality.ORIGINAL -> R.string.stream_quality_original
        MobileStreamQuality.OPUS_192 -> R.string.stream_quality_opus_192
        MobileStreamQuality.OPUS_128 -> R.string.stream_quality_opus_128
    }

internal val ReplayGainMode.label: Int
    get() = when (this) {
        ReplayGainMode.OFF -> R.string.replay_gain_off
        ReplayGainMode.TRACK -> R.string.replay_gain_track
        ReplayGainMode.ALBUM -> R.string.replay_gain_album
        ReplayGainMode.AUTO -> R.string.replay_gain_auto
    }

/** "30 s", "5 min", "1 h" – for pause and rewind durations. */
@Composable
internal fun resumeSecondsLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.settings_resume_rewind_off)
    seconds % 3600 == 0 -> stringResource(R.string.settings_resume_hours, seconds / 3600)
    seconds % 60 == 0 -> stringResource(R.string.settings_resume_minutes, seconds / 60)
    else -> stringResource(R.string.settings_resume_seconds, seconds)
}

/** The two rows "Rewind after pausing for …" and "Rewind by …" including their selection dialogs. */
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

/** The two rows "Rewind via button" and "Fast forward via button" including their selection dialogs. */
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

/** "1.25×" – as in the player, without superfluous zeros. */
@Composable
internal fun speedLabel(speed: Float): String =
    stringResource(R.string.spoken_speed_value, "%.2f".format(speed).trimEnd('0').trimEnd(',', '.'))

/** An area's defaults for speed, trim silence and volume boost (concept 12.4). */
@Composable
internal fun EffectRows(effects: SpokenEffects, keyPrefix: String, onChange: (SpokenEffects) -> Unit) {
    var choosingSpeed by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    var choosingBoost by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    ClickRow(
        title = stringResource(R.string.playback_default_speed),
        value = speedLabel(effects.speed),
        icon = DesignR.drawable.ic_speed,
        onClick = { choosingSpeed = true },
    )
    SwitchRow(
        title = stringResource(R.string.playback_skip_silence),
        summary = stringResource(R.string.playback_skip_silence_summary),
        icon = DesignR.drawable.ic_content_cut,
        checked = effects.skipSilence,
        onCheckedChange = { onChange(effects.copy(skipSilence = it)) },
    )
    ClickRow(
        title = stringResource(R.string.playback_volume_boost),
        value = volumeBoostLabel(effects.volumeBoostDb),
        icon = DesignR.drawable.ic_volume_up,
        onClick = { choosingBoost = true },
    )
    if (choosingSpeed) {
        ChoiceDialog(
            title = stringResource(R.string.playback_default_speed),
            options = (SpokenEffects.SPEED_CHOICES + effects.speed).distinct().sorted(),
            selected = effects.speed,
            label = { speedLabel(it) },
            onSelect = {
                onChange(effects.copy(speed = it))
                choosingSpeed = false
            },
            onDismiss = { choosingSpeed = false },
        )
    }
    if (choosingBoost) {
        ChoiceDialog(
            title = stringResource(R.string.playback_volume_boost),
            options = SpokenEffects.VOLUME_BOOST_CHOICES,
            selected = effects.volumeBoostDb,
            label = { volumeBoostLabel(it) },
            onSelect = {
                onChange(effects.copy(volumeBoostDb = it))
                choosingBoost = false
            },
            onDismiss = { choosingBoost = false },
        )
    }
}

/** Name of an area as in the settings overview. */
internal fun areaTitle(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> R.string.settings_music
    SpokenMode.PODCASTS -> R.string.settings_podcasts
    SpokenMode.BOOKS -> R.string.settings_audiobooks
}

/** Where an area's content comes from (General → Areas). */
internal fun areaSummary(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> R.string.settings_area_music_summary
    SpokenMode.PODCASTS -> R.string.settings_area_podcasts_summary
    SpokenMode.BOOKS -> R.string.settings_area_books_summary
}

/** Icon of an area as in the settings overview. */
internal fun areaIcon(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> DesignR.drawable.ic_music_note
    SpokenMode.PODCASTS -> DesignR.drawable.ic_playlist_play
    SpokenMode.BOOKS -> DesignR.drawable.ic_book
}
