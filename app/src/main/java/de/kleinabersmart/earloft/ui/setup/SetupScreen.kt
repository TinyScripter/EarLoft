package de.kleinabersmart.earloft.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.navigation.SetupPage
import de.kleinabersmart.earloft.ui.components.AppLogo
import de.kleinabersmart.earloft.ui.components.ListGroup
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.launchOrHint
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.permission.NotificationPermission
import de.kleinabersmart.earloft.ui.permission.rememberAudioPermissionRequester
import de.kleinabersmart.earloft.ui.permission.rememberNotificationPermissionRequester
import de.kleinabersmart.earloft.ui.settings.FullBackupViewModel
import de.kleinabersmart.earloft.ui.settings.SettingsUiState
import de.kleinabersmart.earloft.ui.settings.SettingsViewModel
import de.kleinabersmart.earloft.ui.settings.rememberFullBackupImport
import de.kleinabersmart.earloft.ui.spoken.AudiobookshelfViewModel
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.wear.WatchSettings
import de.kleinabersmart.earloft.wear.WatchSetup
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * The pages that apply on this device, for the enabled areas ([areas]) and with or without a watch ([watch]),
 * in their order.
 */
internal fun visibleSetupPages(areas: List<SpokenMode>, watch: Boolean): List<SetupPage> = SetupPage.entries.filter { page ->
    when (page) {
        SetupPage.WATCH -> watch
        SetupPage.MUSIC -> SpokenMode.MUSIC in areas
        SetupPage.PODCASTS -> SpokenMode.PODCASTS in areas
        SetupPage.BOOKS -> SpokenMode.BOOKS in areas
        SetupPage.NOTIFICATIONS -> NotificationPermission.isRequired
        else -> true
    }
}

/**
 * The pages like [visibleSetupPages], always including the currently shown [current]: while areas or watch are still loading,
 * it would otherwise be missing ("Step 1", no "Back", "Next" to the wrong place, review D27). [watch] `null`: not yet known.
 */
internal fun setupPages(areas: List<SpokenMode>, watch: WatchSetup?, current: SetupPage): List<SetupPage> =
    visibleSetupPages(areas, watch = watch != null && watch != WatchSetup.NONE)
        .let { pages -> if (current in pages) pages else (pages + current).sortedBy { it.ordinal } }

/**
 * One page of the setup wizard (concept 15). Every setting on it takes effect immediately; finishing therefore discards
 * nothing. [onNext] opens the next page, [onFinish] ends the wizard, [onSkip] asks first. The
 * `on…` forms open on top of the wizard and return here with "Back".
 */
@Composable
fun SetupScreen(
    page: SetupPage,
    onNext: (SetupPage) -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    onAddMusicServer: () -> Unit,
    onEditMusicServer: (sourceId: String) -> Unit,
    onAddPodcast: () -> Unit,
    onAddAudiobookshelf: () -> Unit,
    onEditAudiobookshelf: (serverId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    watchViewModel: SetupWatchViewModel = hiltViewModel(),
    backupViewModel: FullBackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val watch by watchViewModel.watch.collectAsStateWithLifecycle()
    val watchSettings by watchViewModel.settings.collectAsStateWithLifecycle()
    // Only the books page lists Audiobookshelf servers; an entry's page never changes.
    val absServers = if (page == SetupPage.BOOKS) {
        val absViewModel = hiltViewModel<AudiobookshelfViewModel>()
        val list by absViewModel.list.collectAsStateWithLifecycle()
        val withoutKey by absViewModel.withoutKey.collectAsStateWithLifecycle()
        list.valueOrNull.orEmpty().map { SetupBookServer(it, keyLost = it.serverId in withoutKey) }
    } else {
        emptyList()
    }
    // Enable only with permission, as on the settings pages.
    val requestLocalMusic = rememberAudioPermissionRequester { if (it) viewModel.setUseLocalMedia(true) }
    val requestLocalPodcasts = rememberAudioPermissionRequester { if (it) viewModel.setUseLocalPodcasts(true) }
    val requestLocalBooks = rememberAudioPermissionRequester { if (it) viewModel.setUseLocalBooks(true) }
    // Import as in the settings; snackbars report the results.
    val restoreBackup = rememberFullBackupImport(backupViewModel)
    val podcastDatabaseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImportData(uri)
    }
    val actions = SetupActions(
        onRestoreBackup = restoreBackup,
        onAddMusicServer = onAddMusicServer,
        onEditMusicServer = onEditMusicServer,
        onAddPodcast = onAddPodcast,
        onImportPodcastDatabase = { podcastDatabaseLauncher.launchOrHint(arrayOf("*/*"), context) },
        onAddAudiobookshelf = onAddAudiobookshelf,
        onEditAudiobookshelf = onEditAudiobookshelf,
        setArea = viewModel::setAreaEnabled,
        setLocalMusic = { if (it) requestLocalMusic() else viewModel.setUseLocalMedia(false) },
        setLocalPodcasts = { if (it) requestLocalPodcasts() else viewModel.setUseLocalPodcasts(false) },
        setLocalBooks = { if (it) requestLocalBooks() else viewModel.setUseLocalBooks(false) },
        setDirectory = viewModel::setPodcastDirectory,
        setArtistImages = viewModel::setArtistImages,
        setAlbumCovers = viewModel::setAlbumCovers,
        setOnlineLyrics = viewModel::setOnlineLyrics,
        setAuthorImages = viewModel::setAuthorImages,
        setArdSoundsBooks = viewModel::setArdSoundsBooks,
    )
    val moreActions = SetupMoreActions(
        setWatchQuality = watchViewModel::setQuality,
        setWatchRequireCharging = watchViewModel::setRequireCharging,
        setThemeMode = viewModel::setThemeMode,
        setSystemColors = viewModel::setSystemColors,
        setPlayerCoverColors = viewModel::setPlayerCoverColors,
    )
    SetupContent(
        page, state, absServers, watch, watchSettings, actions, moreActions, onNext, onBack, onSkip, onFinish, modifier,
    )
}

@Composable
private fun SetupContent(
    page: SetupPage,
    state: SettingsUiState,
    absServers: List<SetupBookServer>,
    watch: WatchSetup?,
    watchSettings: WatchSettings,
    actions: SetupActions,
    moreActions: SetupMoreActions,
    onNext: (SetupPage) -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = setupPages(state.areas, watch, page)
    val index = pages.indexOf(page)
    val next = pages.getOrNull(index + 1)
    SetupScaffold(
        step = index + 1,
        steps = pages.size,
        // Until areas and watch are known, the number of steps isn't right yet.
        stepsKnown = !state.isLoading && watch != null,
        nextLabel = when (page) {
            SetupPage.WELCOME -> R.string.setup_start
            SetupPage.DONE -> R.string.setup_finish
            else -> R.string.setup_next
        },
        onNext = { if (next == null) onFinish() else onNext(next) },
        onBack = onBack.takeIf { index > 0 },
        onSkip = onSkip.takeIf { next != null },
        modifier = modifier,
    ) {
        when (page) {
            SetupPage.WELCOME -> welcomePage(actions)
            SetupPage.AREAS -> areasPage(state, actions)
            SetupPage.MUSIC -> musicPage(state, actions)
            SetupPage.PODCASTS -> podcastsPage(state, actions)
            SetupPage.BOOKS -> booksPage(state, absServers, actions)
            SetupPage.NOTIFICATIONS -> notificationsPage()
            SetupPage.WATCH -> watchPage(watch ?: WatchSetup.NONE, watchSettings, moreActions)
            SetupPage.DESIGN -> designPage(state, moreActions)
            SetupPage.DONE -> donePage()
        }
    }
}

/** Header with progress and "Skip", the page as a list, "Back" and "Next" at the bottom. */
@Composable
private fun SetupScaffold(
    step: Int,
    steps: Int,
    stepsKnown: Boolean,
    nextLabel: Int,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) = Surface(modifier.fillMaxSize()) {
    val lowHeight = isLowHeight()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.readableWidth().padding(start = 24.dp, end = 8.dp).height(if (lowHeight) 48.dp else 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.setup_step, step, steps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).alpha(if (stepsKnown) 1f else 0f),
            )
            if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.setup_skip)) }
        }
        LinearProgressIndicator(
            progress = { step.toFloat() / steps },
            modifier = Modifier.readableWidth().padding(horizontal = 24.dp).fillMaxWidth().alpha(if (stepsKnown) 1f else 0f),
        )
        LazyColumn(
            Modifier.weight(1f).readableWidth(),
            contentPadding = PaddingValues(vertical = if (lowHeight) 12.dp else 24.dp),
            content = content,
        )
        Row(
            Modifier.readableWidth().padding(horizontal = 16.dp, vertical = if (lowHeight) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext) { Text(stringResource(nextLabel)) }
        }
    }
}

/** Little height (phone in landscape): header, image and spacing smaller, so enough of the page itself is visible. */
@Composable
private fun isLowHeight(): Boolean =
    !currentWindowAdaptiveInfoV2().windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

/** Heading of a page: image (or logo), title and a short text, centered. Without icon when height is limited. */
internal fun LazyListScope.pageHeader(title: Int, text: Int, icon: Int? = null, logo: Boolean = false) {
    item(key = "header") {
        val lowHeight = isLowHeight()
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (logo) {
                AppLogo(Modifier.height(if (lowHeight) 48.dp else 72.dp))
                Spacer(Modifier.height(if (lowHeight) 12.dp else 24.dp))
            } else if (icon != null && !lowHeight) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
            Text(
                stringResource(title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A hint below the heading or a group, smaller. */
internal fun LazyListScope.pageHint(key: String, text: Int) {
    item(key = key) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

private fun LazyListScope.welcomePage(actions: SetupActions) {
    pageHeader(R.string.setup_welcome_title, R.string.setup_welcome_text, logo = true)
    pageHint("hint", R.string.setup_welcome_hint)
    // Anyone who already has a backup restores it right away; the following pages then show what came back.
    group("backup", R.string.settings_section_backup) {
        row("restore") {
            ActionRow(
                stringResource(R.string.settings_backup_import),
                stringResource(R.string.setup_backup_summary),
                DesignR.drawable.ic_download,
                actions.onRestoreBackup,
            )
        }
    }
}

private fun LazyListScope.notificationsPage() {
    pageHeader(R.string.setup_notifications_title, R.string.setup_notifications_text, icon = DesignR.drawable.ic_notifications)
    item(key = "allow") { NotificationsAllow() }
}

/** "Allow" or the note that it is allowed; also after allowing it in the system settings. */
@Composable
private fun NotificationsAllow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = NotificationPermission.isGranted(context)
        onPauseOrDispose { }
    }
    val request = rememberNotificationPermissionRequester { granted = it }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (granted) {
            ListGroup(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(DesignR.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(16.dp))
                    Text(stringResource(R.string.setup_notifications_granted), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            Button(onClick = request) { Text(stringResource(R.string.setup_notifications_allow)) }
        }
    }
}

private fun LazyListScope.donePage() {
    pageHeader(R.string.setup_done_title, R.string.setup_done_text, icon = DesignR.drawable.ic_check)
}

/** Confirmation before finishing via "Skip" or "Back" on the first page. */
@Composable
fun SetupExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_exit_title)) },
        text = { Text(stringResource(R.string.setup_exit_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.setup_exit_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_exit_continue)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun SetupWelcomePreview() {
    EarLoftTheme {
        val actions = SetupActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        val moreActions = SetupMoreActions({}, {}, {}, {}, {})
        SetupContent(
            SetupPage.WELCOME, SettingsUiState(isLoading = false), emptyList(), WatchSetup.NONE, WatchSettings(), actions, moreActions,
            onNext = {}, onBack = {}, onSkip = {}, onFinish = {},
        )
    }
}
