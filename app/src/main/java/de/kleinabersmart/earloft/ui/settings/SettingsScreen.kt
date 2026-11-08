package de.kleinabersmart.earloft.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.ui.components.LocalWatchController
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Übersicht der Einstellungen: Allgemein, Musik, Podcasts, Bücher, Uhr (nur mit gekoppelter Uhr), Über.
 * Jeder Eintrag öffnet eine Unterseite und zeigt eine Zeile Status.
 */
@Composable
fun SettingsScreen(
    onOpenGeneral: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenAudiobooks: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        watchAvailable = LocalWatchController.current?.isAvailable == true,
        onOpenGeneral = onOpenGeneral,
        onOpenMusic = onOpenMusic,
        onOpenPodcasts = onOpenPodcasts,
        onOpenAudiobooks = onOpenAudiobooks,
        onOpenWatch = onOpenWatch,
        onOpenAbout = onOpenAbout,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    watchAvailable: Boolean,
    onOpenGeneral: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenAudiobooks: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        if (state.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            group("sections") {
                row("general") {
                    NavRow(
                        title = stringResource(R.string.settings_general),
                        summary = stringResource(R.string.settings_general_summary),
                        icon = DesignR.drawable.ic_settings,
                        onClick = onOpenGeneral,
                    )
                }
                // Ausgeblendete Bereiche (Allgemein → Bereiche) haben keine Unterseite.
                if (SpokenMode.MUSIC in state.areas) row("music") {
                    val servers = pluralStringResource(R.plurals.settings_server_count, state.servers.size, state.servers.size)
                    NavRow(
                        title = stringResource(R.string.settings_music),
                        summary = if (state.useLocalMedia) {
                            stringResource(R.string.settings_music_summary_local, servers)
                        } else {
                            servers
                        },
                        icon = DesignR.drawable.ic_music_note,
                        onClick = onOpenMusic,
                    )
                }
                if (SpokenMode.PODCASTS in state.areas) row("podcasts") {
                    NavRow(
                        title = stringResource(R.string.settings_podcasts),
                        summary = pluralStringResource(
                            R.plurals.settings_podcast_count,
                            state.podcastFeedCount,
                            state.podcastFeedCount,
                        ),
                        icon = DesignR.drawable.ic_playlist_play,
                        onClick = onOpenPodcasts,
                    )
                }
                if (SpokenMode.BOOKS in state.areas) row("audiobooks") {
                    NavRow(
                        title = stringResource(R.string.settings_audiobooks),
                        summary = pluralStringResource(
                            R.plurals.settings_server_count,
                            state.audiobookServerCount,
                            state.audiobookServerCount,
                        ),
                        icon = DesignR.drawable.ic_book,
                        onClick = onOpenAudiobooks,
                    )
                }
                // Nur mit gekoppelter Uhr, auf der EarLoft installiert ist (Wear-Konzept 5).
                if (watchAvailable) {
                    row("watch") {
                        NavRow(
                            title = stringResource(R.string.settings_watch),
                            summary = stringResource(R.string.settings_watch_open_summary),
                            icon = DesignR.drawable.ic_watch,
                            onClick = onOpenWatch,
                        )
                    }
                }
                row("about") {
                    NavRow(
                        title = stringResource(R.string.settings_about),
                        summary = stringResource(R.string.settings_about_summary),
                        icon = DesignR.drawable.ic_info,
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, summary: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    EarLoftTheme {
        SettingsContent(
            state = SettingsUiState(audiobookServerCount = 1),
            watchAvailable = true,
            onOpenGeneral = {},
            onOpenMusic = {},
            onOpenPodcasts = {},
            onOpenAudiobooks = {},
            onOpenWatch = {},
            onOpenAbout = {},
        )
    }
}
