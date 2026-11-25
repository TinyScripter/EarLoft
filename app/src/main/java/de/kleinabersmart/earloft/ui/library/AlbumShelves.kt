package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ColorFamily
import de.kleinabersmart.earloft.core.model.colorFamilyOf
import de.kleinabersmart.earloft.ui.components.AlbumCard
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.PinnableHeaderBox
import de.kleinabersmart.earloft.ui.components.ScrollToTopEffect
import de.kleinabersmart.earloft.ui.components.Shelf
import de.kleinabersmart.earloft.ui.components.ShelfInset
import de.kleinabersmart.earloft.ui.components.foldedLetterOf
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll
import de.kleinabersmart.earloft.ui.spoken.ShowStartOnNewFront
import de.kleinabersmart.earloft.ui.spoken.rememberFrontRow
import de.kleinabersmart.earloft.ui.spoken.rowItemAnimation
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** What a shelf of the [AlbumShelves] holds, by the order of the albums. */
sealed interface ShelfLabel {
    data class Letter(val letter: String) : ShelfLabel
    data class ArtistName(val name: String) : ShelfLabel
    data class Decade(val startYear: Int) : ShelfLabel
    data object NoYear : ShelfLabel
    data class Month(val year: Int, val month: Int) : ShelfLabel
    data class Played(val bucket: PlayedBucket) : ShelfLabel
    data class Color(val family: ColorFamily) : ShelfLabel
}

/** Padding inside the cards of a shelf, as on the start page. */
private val CardInset = 4.dp

/** When an album was last played, relative to today. */
enum class PlayedBucket { TODAY, WEEK, MONTH, YEAR, EARLIER, NEVER }

data class AlbumShelf(val label: ShelfLabel, val albums: List<Album>)

/**
 * The [albums] (in the order [sort]) on shelves: title → initial letter, artist → per artist, year → decade (and "no year"),
 * recently added → month, recently played → today / this week / … / never, cover color → color family. Albums with the same
 * label share one shelf, in the order of their first album (an "Ä" title sorted to the end joins the "A" shelf). [now] and
 * [zone] decide months and the played buckets.
 */
fun albumShelves(albums: List<Album>, sort: AlbumSort, now: Long, zone: ZoneId): List<AlbumShelf> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val shelves = LinkedHashMap<ShelfLabel, MutableList<Album>>()
    albums.forEach { album -> shelves.getOrPut(shelfLabelOf(album, sort, today, zone)) { mutableListOf() } += album }
    return shelves.map { (label, list) -> AlbumShelf(label, list) }
}

private fun shelfLabelOf(album: Album, sort: AlbumSort, today: LocalDate, zone: ZoneId): ShelfLabel = when (sort) {
    AlbumSort.TITLE -> ShelfLabel.Letter(foldedLetterOf(album.title.sortKey()))
    AlbumSort.ARTIST -> ShelfLabel.ArtistName(album.artist.trim())
    AlbumSort.YEAR -> album.year?.takeIf { it >= 1000 }?.let { ShelfLabel.Decade(it / 10 * 10) } ?: ShelfLabel.NoYear
    AlbumSort.RECENTLY_ADDED -> {
        val month = YearMonth.from(Instant.ofEpochMilli(album.addedAt ?: 0L).atZone(zone))
        ShelfLabel.Month(month.year, month.monthValue)
    }
    AlbumSort.RECENTLY_PLAYED -> ShelfLabel.Played(playedBucket(album.lastPlayedAt, today, zone))
    AlbumSort.COLOR -> ShelfLabel.Color(colorFamilyOf(album.seedColor))
}

private fun playedBucket(playedAt: Long?, today: LocalDate, zone: ZoneId): PlayedBucket {
    if (playedAt == null) return PlayedBucket.NEVER
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(playedAt).atZone(zone).toLocalDate(), today)
    return when {
        days <= 0 -> PlayedBucket.TODAY
        days < 7 -> PlayedBucket.WEEK
        days < 31 -> PlayedBucket.MONTH
        days < 366 -> PlayedBucket.YEAR
        else -> PlayedBucket.EARLIER
    }
}

/**
 * Albums on shelves by their order ([albumShelves]), the [de.kleinabersmart.earloft.core.model.AlbumView.SHELVES] of the
 * [AlbumBrowser]; each shelf swipes sideways and has "Play" for all its albums ([onPlayShelf]). The fast scroller shows the
 * shelf (initial letter for artists).
 */
@Composable
internal fun AlbumShelves(
    albums: List<Album>,
    sort: AlbumSort,
    onAlbumClick: (Long) -> Unit,
    header: (@Composable () -> Unit)?,
    pinHeader: Boolean,
    resetKey: Any?,
    playingAlbumId: Long?,
    onPlay: (Long) -> Unit,
    onPlayShelf: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shelves = remember(albums, sort) { albumShelves(albums, sort, System.currentTimeMillis(), ZoneId.systemDefault()) }
    val titles = shelves.map { shelfTitle(it.label) }
    val state = rememberLazyListState()
    ScrollToTopEffect(state)
    // Without a hint: shelves reorder with every play and sort recalculation, "New at the start" would pop up all the time.
    ShowStartOnNewFront(shelves, state, { it.label }, announce = false, resetKey = resetKey)
    PinnableHeaderBox(header, pinHeader, modifier.fillMaxSize()) { inlineHeader ->
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
            if (inlineHeader != null) {
                item(key = "header") { Box(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp)) { inlineHeader() } }
            }
            items(shelves.size, key = { shelves[it].label.toString() }) { index ->
                val shelf = shelves[index]
                val title = titles.getOrElse(index) { "" }
                Shelf(
                    title = title,
                    action = {
                        IconButton(onClick = { onPlayShelf(shelf.albums.map { it.id }) }) {
                            Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(R.string.shelf_play, title))
                        }
                    },
                ) {
                    // A played or new album moves to the front: without this the row would hold on to its previous first album (review T47).
                    val row = rememberFrontRow(shelf.albums, announce = false) { it.id }
                    LazyRow(
                        state = row.state,
                        modifier = Modifier.keepHorizontalScroll(row.state),
                        contentPadding = PaddingValues(horizontal = ShelfInset - CardInset),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(row.items, key = { it.id }) { album ->
                            AlbumCard(
                                album = album,
                                onClick = { onAlbumClick(album.id) },
                                modifier = Modifier.rowItemAnimation(this),
                                width = 144.dp,
                                onPlay = { onPlay(album.id) },
                                playing = album.id == playingAlbumId,
                                contentPadding = CardInset,
                            )
                        }
                    }
                }
            }
        }
        val offset = if (inlineHeader != null) 1 else 0
        val bubbles = shelves.mapIndexed { index, shelf ->
            (shelf.label as? ShelfLabel.ArtistName)?.let { foldedLetterOf(it.name.sortKey()) } ?: titles[index]
        }
        LazyListFastScroller(state, labelAt = { index -> bubbles.getOrNull((index - offset).coerceAtLeast(0)).orEmpty() })
    }
}

@Composable
private fun shelfTitle(label: ShelfLabel): String = when (label) {
    is ShelfLabel.Letter -> label.letter
    is ShelfLabel.ArtistName -> label.name.ifEmpty { "#" }
    is ShelfLabel.Decade -> decadeLabel(label.startYear)
    ShelfLabel.NoYear -> stringResource(R.string.shelf_no_year)
    is ShelfLabel.Month -> {
        val locale = LocalConfiguration.current.locales[0]
        remember(label, locale) {
            YearMonth.of(label.year, label.month).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                .replaceFirstChar { it.titlecase(locale) }
        }
    }
    is ShelfLabel.Played -> stringResource(
        when (label.bucket) {
            PlayedBucket.TODAY -> R.string.shelf_played_today
            PlayedBucket.WEEK -> R.string.shelf_played_week
            PlayedBucket.MONTH -> R.string.shelf_played_month
            PlayedBucket.YEAR -> R.string.shelf_played_year
            PlayedBucket.EARLIER -> R.string.shelf_played_earlier
            PlayedBucket.NEVER -> R.string.shelf_played_never
        },
    )
    is ShelfLabel.Color -> colorFamilyName(label.family)
}

@Composable
internal fun colorFamilyName(family: ColorFamily): String = stringResource(
    when (family) {
        ColorFamily.RED -> R.string.color_red
        ColorFamily.ORANGE -> R.string.color_orange
        ColorFamily.YELLOW -> R.string.color_yellow
        ColorFamily.GREEN -> R.string.color_green
        ColorFamily.CYAN -> R.string.color_cyan
        ColorFamily.BLUE -> R.string.color_blue
        ColorFamily.PURPLE -> R.string.color_purple
        ColorFamily.PINK -> R.string.color_pink
        ColorFamily.NEUTRAL -> R.string.color_neutral
        ColorFamily.NONE -> R.string.color_none
    },
)
