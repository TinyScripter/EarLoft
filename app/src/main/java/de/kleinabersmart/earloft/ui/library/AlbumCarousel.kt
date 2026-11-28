package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.background
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.floor
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
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
    // Where the pressed cover was, in the carousel's own coordinates: the menu lives outside the items, which are composed by
    // position, so a new order of the list (a listening event with "Recently played") keeps its state (review4 Y26).
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    val container = remember { CoordinatesHolder() }
    val touched = remember { TouchedItem() }
    val drawInfos = remember { HashMap<Int, CarouselItemDrawInfo>() }
    val defaultFling = CarouselDefaults.singleAdvanceFlingBehavior(state)
    val flingVelocity = with(LocalDensity.current) { ALBUM_FLING_VELOCITY.toPx() }
    val snapVelocity = with(LocalDensity.current) { ALBUM_SNAP_VELOCITY.toPx() }
    val fling = remember(defaultFling, flingVelocity, snapVelocity) {
        AlbumFling(
            defaultFling,
            position = { carouselPosition(drawInfos, state.currentItem) },
            touchedItem = { touched.index },
            flingVelocity = flingVelocity,
            snapVelocity = snapVelocity,
        )
    }

    // As in the crate: a new sort or filter starts at the first album, otherwise the current one stays.
    val lastReset = remember { CarouselResetKey(resetKey) }
    LaunchedEffect(albums, resetKey) {
        val current = state.currentItem
        // As in the crate (review4 T52): while the carousel moves (fling, random run, slider), a new list must not break it off with a
        // jump; it moves on through the new list, and only the front album is noted (review5 P57).
        if (lastReset.key == resetKey && (state.isScrollInProgress || sliding)) {
            frontId = albums.getOrNull(current.coerceIn(0, (albums.size - 1).coerceAtLeast(0)))?.id
            return@LaunchedEffect
        }
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

    Box(modifier.fillMaxSize().onGloballyPositioned { container.value = it }) {
        Column(Modifier.fillMaxSize()) {
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
                // The neighbors have exactly the rest of the carousel's width, as minimum and maximum: the carousel spreads its
                // keylines over its full width (the content padding only shifts the first and last item) and picks as many large
                // items as fit next to the smallest allowed neighbors. With a cover limited by the height (filter row, mini player)
                // two of them fit, and two covers stood large side by side while only one counted. At most half the cover, so the
                // current album stands out; the carousel then gets narrower than the row instead of a second large cover.
                val rowWidth = if (wide) maxWidth * (1.4f / 2.4f) else maxWidth
                val small = ((rowWidth - hero - CAROUSEL_SPACING * 2) / 2).coerceIn(MIN_SMALL, min(MAX_SMALL, hero * 0.5f))
                val carouselWidth = min(rowWidth, hero + CAROUSEL_SPACING * 2 + small * 2)
                val carousel: @Composable (Modifier) -> Unit = { columnModifier ->
                    Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalCenteredHeroCarousel(
                            state = state,
                            // The slider below is measured first: with little height the cover gets lower, the slider keeps its size
                            // (review4 Y25). The gap above outside the height, so the cover is as high as it is wide.
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .width(carouselWidth)
                                .padding(top = 12.dp)
                                .height(hero)
                                .pointerInput(state) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        touched.index = state.currentItem
                                    }
                                },
                            maxItemWidth = hero,
                            flingBehavior = fling,
                            itemSpacing = CAROUSEL_SPACING,
                            minSmallItemWidth = small,
                            maxSmallItemWidth = small,
                            contentPadding = PaddingValues(horizontal = CAROUSEL_PADDING),
                        ) { index ->
                            val album = albums[index]
                            val current = index == frontIndex
                            val cover = remember { CoordinatesHolder() }
                            val drawInfo = carouselItemDrawInfo
                            DisposableEffect(index, drawInfo) {
                                drawInfos[index] = drawInfo
                                onDispose { if (drawInfos[index] === drawInfo) drawInfos.remove(index) }
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { cover.value = it }
                                    .maskClip(MaterialTheme.shapes.extraLarge)
                                    .combinedClickable(
                                        onClickLabel = if (current) stringResource(R.string.album_crate_open) else null,
                                        onClick = { if (current) onAlbumClick(album.id) else scope.launch { state.animateScrollToItem(index) } },
                                        onLongClickLabel = stringResource(R.string.action_more),
                                        onLongClick = {
                                            val at = cover.value?.takeIf { it.isAttached }
                                            menuAnchor = container.value?.takeIf { it.isAttached }?.let { c -> at?.let(c::localBoundingBoxOf) } ?: Rect.Zero
                                            menuFor = album
                                        },
                                    )
                                    .then(
                                        if (current) Modifier.semantics { contentDescription = "${album.title}, ${album.artist}" } else Modifier.clearAndSetSemantics {},
                                    ),
                            ) {
                                CarouselCover(album)
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
                            reserveTwoTitleLines = true,
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
                            reserveTwoTitleLines = true,
                        )
                    }
                }
            }
        }
        // At the cover that was pressed (review4 T51), but outside the items (Y26).
        menuFor?.let { album ->
            key(album.id) {
                val density = LocalDensity.current
                Box(
                    Modifier
                        .offset { IntOffset(menuAnchor.left.roundToInt(), menuAnchor.top.roundToInt()) }
                        .size(with(density) { menuAnchor.width.toDp() }, with(density) { menuAnchor.height.toDp() }),
                ) {
                    AlbumMenu(album, onDismiss = { menuFor = null })
                }
            }
        }
    }
}

/** Holds layout coordinates for reading them in an event, without recomposing when they change. */
private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
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

/** The album in front when the finger came down, read when it lets go (see [AlbumFling]). */
private class TouchedItem(var index: Int = 0)

/** From this speed on a gesture counts as a fling, as in the pager behind the carousel. */
private val ALBUM_FLING_VELOCITY = 400.dp

/** The least speed handed to the pager, so it surely snaps to the next album in the direction (above [ALBUM_FLING_VELOCITY]). */
private val ALBUM_SNAP_VELOCITY = 500.dp

/**
 * Where the carousel stands in albums, e.g. 3.4 between the fourth and the fifth, from how far the covers are opened: the one
 * leaving the middle shrinks to the size of a neighbor while the next grows to full size. `null` while the covers are unknown.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun carouselPosition(drawInfos: Map<Int, CarouselItemDrawInfo>, current: Int): Float? {
    fun opened(index: Int): Float? = drawInfos[index]?.takeIf { it.maxSize > it.minSize }?.let {
        ((it.size - it.minSize) / (it.maxSize - it.minSize)).coerceIn(0f, 1f)
    }
    val middle = opened(current) ?: return null
    val next = opened(current + 1) ?: 0f
    val previous = opened(current - 1) ?: 0f
    return if (next >= previous) {
        current + next / (middle + next).coerceAtLeast(0.001f)
    } else {
        current - previous / (middle + previous).coerceAtLeast(0.001f)
    }
}

/**
 * Lets the carousel settle on the album the gesture leads to: after a fling the neighbor of the album touched, otherwise the
 * closest one. The pager behind the carousel decides with a fixed page size, but at both ends the step to the neighbor is
 * shorter by a neighbor's width (the first album stands at the edge, not in the middle): a swipe from the first album passed
 * the second and landed on the third, a shorter one fell back to the first. The pager still does the snapping, it knows where
 * each album rests; it gets a speed towards the chosen one, as it always snaps to the next resting place in the direction of a
 * fast enough fling.
 */
private class AlbumFling(
    private val delegate: TargetedFlingBehavior,
    private val position: () -> Float?,
    private val touchedItem: () -> Int,
    private val flingVelocity: Float,
    private val snapVelocity: Float,
) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float {
        val scope = this
        val at = position() ?: return with(delegate) { scope.performFling(initialVelocity, onRemainingDistanceUpdated) }
        val before = floor(at)
        val forward = if (abs(initialVelocity) >= flingVelocity) {
            touchedItem() + (if (initialVelocity > 0f) 1 else -1) > before
        } else {
            at - before >= 0.5f
        }
        val velocity = if (forward) initialVelocity.coerceAtLeast(snapVelocity) else initialVelocity.coerceAtMost(-snapVelocity)
        return with(delegate) { scope.performFling(velocity, onRemainingDistanceUpdated) }
    }
}
