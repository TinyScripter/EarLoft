package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.ui.components.LocalScrollToTop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.random.Random
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

private val MAX_HERO = 420.dp
private val MIN_HERO = 120.dp
private val MIN_SMALL = 40.dp
private val MAX_SMALL = 120.dp
private val CAROUSEL_PADDING = 16.dp
private val CAROUSEL_SPACING = 8.dp

/** Height of the details below the carousel (title, artist, line, buttons), so the cover can take the rest. */
private val DETAILS_HEIGHT = 190.dp

/**
 * Albums as a Material carousel, the [de.kleinabersmart.earloft.core.model.AlbumView.CAROUSEL] of the [AlbumBrowser]: the
 * current album large in the middle, its neighbors narrow beside it; swiping moves one album, at the ends the tabs take over.
 * Below: a horizontal [AlbumScrubber] and the [FrontAlbumDetails]. Tapping the middle cover opens the album, a neighbor comes
 * to the middle. Position, reset and restore as in the [AlbumCrate].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumCarousel(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    label: ((Album) -> String)?,
    header: (@Composable () -> Unit)?,
    resetKey: Any?,
    playingAlbumId: Long?,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentAlbums by rememberUpdatedState(albums)
    var frontId by rememberSaveable { mutableStateOf<Long?>(null) }
    val state = rememberCarouselState(albums.indexOfFirst { it.id == frontId }.coerceAtLeast(0)) { currentAlbums.size }
    var sliding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Album?>(null) }

    // As in the crate: a new sort or filter starts at the first album, otherwise the current one stays.
    val lastReset = remember { CarouselResetKey(resetKey) }
    LaunchedEffect(albums, resetKey) {
        val current = state.currentItem
        val target = when {
            // From `null` (filter still loading) to a value is no new filter (review4 T57).
            lastReset.key != null && lastReset.key != resetKey -> 0
            current == 0 -> 0
            else -> albums.indexOfFirst { it.id == frontId }.takeIf { it >= 0 } ?: current.coerceAtMost((albums.size - 1).coerceAtLeast(0))
        }
        lastReset.key = resetKey
        if (current != target) state.scrollToItem(target)
        frontId = albums.getOrNull(target)?.id
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentItem }.distinctUntilChanged().drop(1).collect { index ->
            frontId = currentAlbums.getOrNull(index)?.id
            if (state.isScrollInProgress || sliding) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val scrollToTop = LocalScrollToTop.current
    LaunchedEffect(scrollToTop) { scrollToTop.collect { state.animateScrollToItem(0) } }

    val random: () -> Unit = {
        if (albums.size > 1) scope.launch {
            val current = state.currentItem
            state.animateScrollToItem(Random.nextInt(albums.size - 1).let { if (it >= current) it + 1 else it })
        }
    }

    Column(modifier.fillMaxSize()) {
        if (header != null) Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)) { header() }
        val frontIndex = state.currentItem.coerceIn(0, (albums.size - 1).coerceAtLeast(0))
        val front = albums.getOrNull(frontIndex) ?: return@Column
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth > maxHeight * 1.3f
            val hero = if (wide) {
                min(maxWidth * 0.4f, maxHeight - SCRUBBER_THICKNESS - 24.dp)
            } else {
                min(maxWidth * 0.62f, maxHeight - DETAILS_HEIGHT - SCRUBBER_THICKNESS - 24.dp)
            }.coerceIn(MIN_HERO, MAX_HERO)
            // The neighbors take the rest of the width: with the default (at most 56 dp) the carousel would rather show two
            // narrower large covers than one square one.
            val rowWidth = if (wide) maxWidth * (1.4f / 2.4f) else maxWidth
            val small = ((rowWidth - CAROUSEL_PADDING * 2 - hero - CAROUSEL_SPACING * 2) / 2).coerceIn(MIN_SMALL, MAX_SMALL)
            val carousel: @Composable (Modifier) -> Unit = { columnModifier ->
                Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalCenteredHeroCarousel(
                        state = state,
                        modifier = Modifier.fillMaxWidth().height(hero).padding(top = 12.dp),
                        maxItemWidth = hero,
                        itemSpacing = CAROUSEL_SPACING,
                        minSmallItemWidth = MIN_SMALL,
                        maxSmallItemWidth = small,
                        contentPadding = PaddingValues(horizontal = CAROUSEL_PADDING),
                    ) { index ->
                        val album = albums[index]
                        val current = index == frontIndex
                        Box(
                            Modifier
                                .fillMaxSize()
                                .maskClip(MaterialTheme.shapes.extraLarge)
                                .combinedClickable(
                                    onClickLabel = if (current) stringResource(R.string.album_crate_open) else null,
                                    onClick = { if (current) onAlbumClick(album.id) else scope.launch { state.animateScrollToItem(index) } },
                                    onLongClickLabel = stringResource(R.string.action_more),
                                    onLongClick = { menuFor = album },
                                )
                                .then(
                                    if (current) Modifier.semantics { contentDescription = "${album.title}, ${album.artist}" } else Modifier.clearAndSetSemantics {},
                                ),
                        ) {
                            CarouselCover(album)
                            // At the cover that was pressed, not at the whole carousel (review4 T51).
                            if (menuFor?.id == album.id) AlbumMenu(album, onDismiss = { menuFor = null })
                        }
                    }
                    if (albums.size > 1) {
                        AlbumScrubber(
                            vertical = false,
                            count = albums.size,
                            position = { state.currentItem.toFloat() },
                            frontIndex = frontIndex,
                            frontTitle = front.title,
                            labelAt = { index -> albums.getOrNull(index)?.let { label?.invoke(it) } ?: "#${index + 1}" },
                            onSlide = { sliding = it },
                            onJump = { index -> scope.launch { state.scrollToItem(index.coerceIn(0, albums.size - 1)) } },
                            modifier = Modifier.fillMaxWidth().height(SCRUBBER_THICKNESS).padding(horizontal = 24.dp),
                        )
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    carousel(Modifier.weight(1.4f))
                    FrontAlbumDetails(
                        front, frontIndex, albums.size, front.id == playingAlbumId, { onPlay(front.id) }, random,
                        Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Details first: with little height (filter row, split screen, mini player) the cover gives way, not play, dice and
                    // the menu below it (review4 T49).
                    carousel(Modifier.fillMaxWidth().weight(1f, fill = false))
                    FrontAlbumDetails(
                        front, frontIndex, albums.size, front.id == playingAlbumId, { onPlay(front.id) }, random,
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** The cover filling a carousel item of any width (cropped), the music note while there is none. */
@Composable
private fun CarouselCover(album: Album) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(DesignR.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.3f),
        )
        AsyncImage(
            model = AppUris.albumCover(album.id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The sort and filter at the last list change (see [AlbumCarousel]). */
private class CarouselResetKey(var key: Any?)
