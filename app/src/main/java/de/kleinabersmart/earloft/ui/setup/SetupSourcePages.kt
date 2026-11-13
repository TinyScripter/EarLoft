package de.kleinabersmart.earloft.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.SpokenServer
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.settings.SettingsUiState
import de.kleinabersmart.earloft.ui.settings.SwitchRow
import de.kleinabersmart.earloft.ui.settings.areaIcon
import de.kleinabersmart.earloft.ui.settings.areaSummary
import de.kleinabersmart.earloft.ui.settings.areaTitle
import de.kleinabersmart.earloft.ui.spoken.label
import de.kleinabersmart.earloft.ui.spoken.summary
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Was die Seiten Begrüßung, Bereiche und Quellen auslösen: Sicherung bzw. Podcast-Datenbank einspielen (Dateiauswahl wie in
 * den Einstellungen), Formulare öffnen (Navigation von außen) und Einstellungen schalten (aus dem `SettingsViewModel`, wie
 * auf den Einstellungsseiten). Die Schalter für Dateien auf dem Gerät fragen vorher die Berechtigung an.
 */
internal class SetupActions(
    val onRestoreBackup: () -> Unit,
    val onAddMusicServer: () -> Unit,
    val onEditMusicServer: (sourceId: String) -> Unit,
    val onAddPodcast: () -> Unit,
    val onImportPodcastDatabase: () -> Unit,
    val onAddAudiobookshelf: () -> Unit,
    val onEditAudiobookshelf: (serverId: String) -> Unit,
    val setArea: (SpokenMode, Boolean) -> Unit,
    val setLocalMusic: (Boolean) -> Unit,
    val setLocalPodcasts: (Boolean) -> Unit,
    val setLocalBooks: (Boolean) -> Unit,
    val setDirectory: (PodcastDirectory, Boolean) -> Unit,
    val setArtistImages: (Boolean) -> Unit,
    val setAlbumCovers: (Boolean) -> Unit,
    val setOnlineLyrics: (Boolean) -> Unit,
    val setAuthorImages: (Boolean) -> Unit,
)

/** Musik, Podcasts, Bücher an oder aus; die Quellen-Seiten danach gibt es nur für die eingeschalteten. */
internal fun LazyListScope.areasPage(state: SettingsUiState, actions: SetupActions) {
    pageHeader(R.string.setup_areas_title, R.string.setup_areas_text, icon = DesignR.drawable.ic_settings)
    group("areas") {
        SpokenMode.entries.forEach { area ->
            row("area-${area.name}") {
                val enabled = area in state.areas
                SwitchRow(
                    title = stringResource(areaTitle(area)),
                    summary = stringResource(areaSummary(area)),
                    icon = areaIcon(area),
                    checked = enabled,
                    onCheckedChange = { actions.setArea(area, it) },
                    // Der letzte eingeschaltete Bereich bleibt an (wie unter Einstellungen → Allgemein).
                    enabled = !enabled || state.areas.size > 1,
                )
            }
        }
    }
    pageHint("areas-hint", R.string.settings_areas_hint)
}

internal fun LazyListScope.musicPage(state: SettingsUiState, actions: SetupActions) {
    pageHeader(R.string.settings_music, R.string.setup_music_text, icon = areaIcon(SpokenMode.MUSIC))
    group("servers", R.string.setup_section_servers) {
        state.servers.forEach { item ->
            row(item.server.sourceId) {
                SourceRow(item.server.name, item.server.baseUrl, DesignR.drawable.ic_cloud) { actions.onEditMusicServer(item.server.sourceId) }
            }
        }
        row("add-server") { AddRow(stringResource(R.string.settings_add_server), actions.onAddMusicServer) }
    }
    group("device", R.string.setup_section_device) {
        row("local-media") {
            SwitchRow(
                title = stringResource(R.string.settings_local_media),
                summary = stringResource(R.string.settings_local_media_summary),
                icon = DesignR.drawable.ic_music_note,
                checked = state.useLocalMedia,
                onCheckedChange = actions.setLocalMusic,
            )
        }
    }
    group("online", R.string.settings_section_online) {
        row("artist-images") {
            SwitchRow(
                title = stringResource(R.string.settings_artist_images),
                summary = stringResource(R.string.settings_artist_images_summary),
                icon = DesignR.drawable.ic_person,
                checked = state.artistImages,
                onCheckedChange = actions.setArtistImages,
            )
        }
        row("album-covers") {
            SwitchRow(
                title = stringResource(R.string.settings_album_covers),
                summary = stringResource(R.string.settings_album_covers_summary),
                icon = DesignR.drawable.ic_music_note,
                checked = state.albumCovers,
                onCheckedChange = actions.setAlbumCovers,
            )
        }
        row("online-lyrics") {
            SwitchRow(
                title = stringResource(R.string.settings_online_lyrics),
                summary = stringResource(R.string.settings_online_lyrics_summary),
                icon = DesignR.drawable.ic_lyrics,
                checked = state.onlineLyrics,
                onCheckedChange = actions.setOnlineLyrics,
            )
        }
    }
}

internal fun LazyListScope.podcastsPage(state: SettingsUiState, actions: SetupActions) {
    pageHeader(R.string.settings_podcasts, R.string.setup_podcasts_text, icon = areaIcon(SpokenMode.PODCASTS))
    group("subscriptions", R.string.setup_section_subscriptions) {
        if (state.podcastFeedCount > 0) {
            row("subscribed") {
                SourceRow(
                    pluralStringResource(R.plurals.count_podcasts, state.podcastFeedCount, state.podcastFeedCount),
                    subtitle = null,
                    icon = DesignR.drawable.ic_playlist_play,
                )
            }
        }
        row("add-podcast") { AddRow(stringResource(R.string.podcast_add), actions.onAddPodcast) }
        // Abos samt Fortschritt aus einer anderen Podcast-App (wie Einstellungen → Podcasts).
        row("import-database") {
            ActionRow(
                stringResource(R.string.settings_db_import),
                stringResource(R.string.settings_db_import_summary),
                DesignR.drawable.ic_download,
                actions.onImportPodcastDatabase,
            )
        }
    }
    group("directories", R.string.settings_section_directories) {
        state.podcastDirectories.forEach { directory ->
            row("directory-${directory.name}") {
                SwitchRow(
                    title = stringResource(directory.label),
                    summary = stringResource(directory.summary),
                    icon = R.drawable.ic_nav_search,
                    checked = directory in state.enabledPodcastDirectories,
                    onCheckedChange = { actions.setDirectory(directory, it) },
                )
            }
        }
    }
    group("device", R.string.setup_section_device) {
        row("local-podcasts") {
            SwitchRow(
                title = stringResource(R.string.settings_local_podcasts),
                summary = stringResource(R.string.settings_local_podcasts_summary),
                icon = DesignR.drawable.ic_playlist_play,
                checked = state.useLocalPodcasts,
                onCheckedChange = actions.setLocalPodcasts,
            )
        }
    }
}

internal fun LazyListScope.booksPage(state: SettingsUiState, servers: List<SpokenServer>, actions: SetupActions) {
    pageHeader(R.string.settings_audiobooks, R.string.setup_books_text, icon = areaIcon(SpokenMode.BOOKS))
    group("servers", R.string.setup_section_servers) {
        servers.forEach { server ->
            row(server.serverId) {
                SourceRow(server.name, server.baseUrl, DesignR.drawable.ic_book) { actions.onEditAudiobookshelf(server.serverId) }
            }
        }
        row("add-server") { AddRow(stringResource(R.string.spoken_abs_add), actions.onAddAudiobookshelf) }
    }
    group("device", R.string.setup_section_device) {
        row("local-books") {
            SwitchRow(
                title = stringResource(R.string.settings_local_books),
                summary = stringResource(R.string.settings_local_books_summary),
                icon = DesignR.drawable.ic_book,
                checked = state.useLocalBooks,
                onCheckedChange = actions.setLocalBooks,
            )
        }
    }
    group("online", R.string.settings_section_online) {
        row("author-images") {
            SwitchRow(
                title = stringResource(R.string.settings_author_images),
                summary = stringResource(R.string.settings_author_images_summary),
                icon = DesignR.drawable.ic_book,
                checked = state.authorImages,
                onCheckedChange = actions.setAuthorImages,
            )
        }
    }
}

/** Eine schon eingerichtete Quelle; ein Tipp öffnet sie zum Bearbeiten. */
@Composable
private fun SourceRow(title: String, subtitle: String?, icon: Int, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = {
            Icon(painterResource(DesignR.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.server_edit), onClick = onClick) else Modifier,
    )
}

/** Eine Aktion mit Erklärung, etwa eine Datei einspielen. */
@Composable
internal fun ActionRow(title: String, summary: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Öffnet das Formular für eine neue Quelle. */
@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
