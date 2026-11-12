package de.kleinabersmart.earloft.ui.setup

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.SpokenServer
import de.kleinabersmart.earloft.navigation.SetupPage
import de.kleinabersmart.earloft.ui.components.AppLogo
import de.kleinabersmart.earloft.ui.components.ListGroup
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.permission.NotificationPermission
import de.kleinabersmart.earloft.ui.permission.rememberAudioPermissionRequester
import de.kleinabersmart.earloft.ui.permission.rememberNotificationPermissionRequester
import de.kleinabersmart.earloft.ui.settings.SettingsUiState
import de.kleinabersmart.earloft.ui.settings.SettingsViewModel
import de.kleinabersmart.earloft.ui.spoken.AudiobookshelfViewModel
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Die Seiten, die auf diesem Gerät und für die eingeschalteten Bereiche ([areas]) vorkommen, in ihrer Reihenfolge. */
internal fun visibleSetupPages(areas: List<SpokenMode>): List<SetupPage> = SetupPage.entries.filter { page ->
    when (page) {
        SetupPage.MUSIC -> SpokenMode.MUSIC in areas
        SetupPage.PODCASTS -> SpokenMode.PODCASTS in areas
        SetupPage.BOOKS -> SpokenMode.BOOKS in areas
        SetupPage.NOTIFICATIONS -> NotificationPermission.isRequired
        else -> true
    }
}

/**
 * Eine Seite des Einrichtungsassistenten (Konzept 15). Jede Einstellung darauf wirkt sofort; Beenden verwirft deshalb
 * nichts. [onNext] öffnet die folgende Seite, [onFinish] beendet den Assistenten, [onSkip] fragt vorher nach. Die
 * `on…`-Formulare öffnen sich über dem Assistenten und kehren mit „Zurück“ hierher zurück.
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Nur die Bücher-Seite listet Audiobookshelf-Server; die Seite eines Eintrags wechselt nie.
    val absServers = if (page == SetupPage.BOOKS) {
        val list by hiltViewModel<AudiobookshelfViewModel>().list.collectAsStateWithLifecycle()
        list.valueOrNull.orEmpty()
    } else {
        emptyList()
    }
    // Einschalten erst mit Berechtigung, wie auf den Einstellungsseiten.
    val requestLocalMusic = rememberAudioPermissionRequester { if (it) viewModel.setUseLocalMedia(true) }
    val requestLocalPodcasts = rememberAudioPermissionRequester { if (it) viewModel.setUseLocalPodcasts(true) }
    val requestLocalBooks = rememberAudioPermissionRequester { if (it) viewModel.setUseLocalBooks(true) }
    val actions = SetupActions(
        onAddMusicServer = onAddMusicServer,
        onEditMusicServer = onEditMusicServer,
        onAddPodcast = onAddPodcast,
        onAddAudiobookshelf = onAddAudiobookshelf,
        onEditAudiobookshelf = onEditAudiobookshelf,
        setArea = viewModel::setAreaEnabled,
        setLocalMusic = { if (it) requestLocalMusic() else viewModel.setUseLocalMedia(false) },
        setLocalPodcasts = { if (it) requestLocalPodcasts() else viewModel.setUseLocalPodcasts(false) },
        setLocalBooks = { if (it) requestLocalBooks() else viewModel.setUseLocalBooks(false) },
        setDirectory = viewModel::setPodcastDirectory,
        setArtistImages = viewModel::setArtistImages,
        setAlbumCovers = viewModel::setAlbumCovers,
        setAuthorImages = viewModel::setAuthorImages,
    )
    SetupContent(page, state, absServers, actions, onNext, onBack, onSkip, onFinish, modifier)
}

@Composable
private fun SetupContent(
    page: SetupPage,
    state: SettingsUiState,
    absServers: List<SpokenServer>,
    actions: SetupActions,
    onNext: (SetupPage) -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = visibleSetupPages(state.areas)
    val index = pages.indexOf(page).coerceAtLeast(0)
    val next = pages.getOrNull(index + 1)
    SetupScaffold(
        step = index + 1,
        steps = pages.size,
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
            SetupPage.WELCOME -> welcomePage()
            SetupPage.AREAS -> areasPage(state, actions)
            SetupPage.MUSIC -> musicPage(state, actions)
            SetupPage.PODCASTS -> podcastsPage(state, actions)
            SetupPage.BOOKS -> booksPage(state, absServers, actions)
            SetupPage.NOTIFICATIONS -> notificationsPage()
            SetupPage.DONE -> donePage()
        }
    }
}

/** Kopf mit Fortschritt und „Überspringen“, die Seite als Liste, unten „Zurück“ und „Weiter“. */
@Composable
private fun SetupScaffold(
    step: Int,
    steps: Int,
    nextLabel: Int,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) = Surface(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.readableWidth().padding(start = 24.dp, end = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.setup_step, step, steps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.setup_skip)) }
        }
        LinearProgressIndicator(
            progress = { step.toFloat() / steps },
            modifier = Modifier.readableWidth().padding(horizontal = 24.dp).fillMaxWidth(),
        )
        LazyColumn(
            Modifier.weight(1f).readableWidth(),
            contentPadding = PaddingValues(vertical = 24.dp),
            content = content,
        )
        Row(
            Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext) { Text(stringResource(nextLabel)) }
        }
    }
}

/** Überschrift einer Seite: Bild (oder Logo), Titel und ein kurzer Text, mittig. */
internal fun LazyListScope.pageHeader(title: Int, text: Int, icon: Int? = null, logo: Boolean = false) {
    item(key = "header") {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (logo) {
                AppLogo(Modifier.height(72.dp))
            } else if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
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

/** Ein Hinweis unter der Überschrift, kleiner und links. */
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

private fun LazyListScope.welcomePage() {
    pageHeader(R.string.setup_welcome_title, R.string.setup_welcome_text, logo = true)
    pageHint("hint", R.string.setup_welcome_hint)
}

private fun LazyListScope.notificationsPage() {
    pageHeader(R.string.setup_notifications_title, R.string.setup_notifications_text, icon = DesignR.drawable.ic_notifications)
    item(key = "allow") { NotificationsAllow() }
}

/** „Erlauben“ bzw. der Hinweis, dass es erlaubt ist; auch nach dem Erlauben in den Systemeinstellungen. */
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

/** Nachfrage vor dem Beenden über „Überspringen“ oder „Zurück“ auf der ersten Seite. */
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
        val actions = SetupActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {})
        SetupContent(SetupPage.WELCOME, SettingsUiState(), emptyList(), actions, onNext = {}, onBack = {}, onSkip = {}, onFinish = {})
    }
}
