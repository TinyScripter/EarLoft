package de.kleinabersmart.earloft.ui.library

import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.MusicStats
import de.kleinabersmart.earloft.core.model.MusicTopEntry
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.ui.components.ArtistAvatar
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.spoken.yearChipsHeader
import de.kleinabersmart.earloft.ui.spoken.BarChart
import de.kleinabersmart.earloft.ui.spoken.HOUR_MS
import de.kleinabersmart.earloft.ui.spoken.StatBar
import de.kleinabersmart.earloft.ui.spoken.StatCard
import de.kleinabersmart.earloft.ui.spoken.formatListened
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Which top list a row shows; determines image and jump target. */
private enum class TopKind(@StringRes val title: Int) {
    ARTISTS(R.string.music_stats_top_artists),
    ALBUMS(R.string.music_stats_top_albums),
    SONGS(R.string.music_stats_top_songs),
    GENRES(R.string.music_stats_top_genres),
}

/**
 * "Statistics" tab of the music library (concept 8 "Music statistics"): listening time and plays, top lists,
 * history per month and weekday; selectable per year like the statistics of podcasts and books.
 */
@Composable
fun MusicStatsScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicStatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val current = when (val state = stats) {
        is LoadState.Ready -> state.value
        LoadState.Failed, LoadState.Missing -> {
            Box(modifier.fillMaxSize()) { LoadFailedHint() }
            return
        }
        // A spinner like every other list while loading, not an empty page.
        LoadState.Loading -> {
            LoadingIndicator(modifier)
            return
        }
    }
    if (current.year == null && current.plays == 0) {
        Box(modifier.fillMaxSize()) { EmptyHint(stringResource(R.string.music_stats_empty), Modifier.padding(16.dp)) }
        return
    }
    val songIds = current.topSongs.mapNotNull { it.id }
    val onClick: (TopKind, MusicTopEntry) -> Unit = { kind, entry ->
        when (kind) {
            TopKind.ARTISTS -> entry.id?.let(onArtistClick)
            TopKind.ALBUMS -> entry.id?.let(onAlbumClick)
            TopKind.SONGS -> entry.id?.let { viewModel.playTopSongs(songIds, it) }
            TopKind.GENRES -> onGenreClick(entry.title)
        }
    }
    LazyColumn(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        yearChipsHeader(current.years, current.year, viewModel::selectYear)
        current.year?.let { year ->
            if (current.plays == 0) {
                item(key = "year-empty") { EmptyHint(stringResource(R.string.stats_year_empty, year), Modifier.padding(16.dp)) }
                return@LazyColumn
            }
        }
        item(key = "summary") { StatCard { Summary(current) } }
        topList(TopKind.ARTISTS, current.topArtists, onClick)
        topList(TopKind.ALBUMS, current.topAlbums, onClick)
        topList(TopKind.SONGS, current.topSongs, onClick)
        topList(TopKind.GENRES, current.topGenres, onClick)
        item(key = "history") { SectionHeader(stringResource(R.string.podcast_stats_history)) }
        item(key = "chart") {
            val locale = LocalConfiguration.current.locales[0]
            if (current.hasHistory) {
                StatCard {
                    BarChart(
                        current.months.map { StatBar(Month.of(it.month).getDisplayName(TextStyle.SHORT, locale), it.listenedMs) },
                        stringResource(R.string.podcast_stats_hours_per_month),
                        Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (current.weekdays.any { it.listenedMs > 0 }) {
            item(key = "weekdays") {
                val locale = LocalConfiguration.current.locales[0]
                StatCard {
                    BarChart(
                        current.weekdays.map { StatBar(DayOfWeek.of(it.dayOfWeek).getDisplayName(TextStyle.SHORT, locale), it.listenedMs) },
                        stringResource(R.string.book_stats_weekdays),
                        Modifier.padding(16.dp),
                    )
                }
            }
        }
        item(key = "end") { Box(Modifier.height(16.dp)) }
    }
}

private fun LazyListScope.topList(kind: TopKind, entries: List<MusicTopEntry>, onClick: (TopKind, MusicTopEntry) -> Unit) {
    if (entries.isEmpty()) return
    item(key = "top-$kind") { SectionHeader(stringResource(kind.title)) }
    val max = entries.maxOf { it.plays }.coerceAtLeast(1)
    // Genres have no ID; their name stays unchanged, because the query only merges ASCII spellings.
    groupedItems(entries, key = { "$kind-${it.id ?: it.title}" }) { entry ->
        TopRow(kind, entry, share = entry.plays.toFloat() / max, onClick = { onClick(kind, entry) })
    }
}

@Composable
private fun Summary(stats: MusicStats) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatListened(stats.totalMs), style = MaterialTheme.typography.displaySmall)
        val days = (stats.totalMs / (24 * HOUR_MS)).toInt()
        Text(
            if (days >= 2) pluralStringResource(R.plurals.podcast_stats_listened_days, days, days) else stringResource(R.string.podcast_stats_listened),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOf(
                pluralStringResource(R.plurals.music_stats_plays, stats.plays, stats.plays),
                pluralStringResource(R.plurals.song_count, stats.songs, stats.songs),
                pluralStringResource(R.plurals.album_count, stats.albums, stats.albums),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        // In "All time" all played albums are new.
        if (stats.year != null && stats.newAlbums > 0) {
            Text(
                pluralStringResource(R.plurals.music_stats_new_albums, stats.newAlbums, stats.newAlbums),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TopRow(kind: TopKind, entry: MusicTopEntry, share: Float, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val plays = pluralStringResource(R.plurals.music_stats_plays, entry.plays, entry.plays)
                Text(listOfNotNull(entry.subtitle?.takeIf { it.isNotBlank() }, plays).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        },
        leadingContent = {
            when (kind) {
                TopKind.ARTISTS -> entry.id?.let { ArtistAvatar(it, Modifier.size(48.dp), iconSize = 28.dp) }
                TopKind.ALBUMS, TopKind.SONGS -> AlbumArt(entry.albumId, Modifier.size(48.dp))
                TopKind.GENRES -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(DesignR.drawable.ic_music_note), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = { Text(formatListened(entry.listenedMs), style = MaterialTheme.typography.titleSmall) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
