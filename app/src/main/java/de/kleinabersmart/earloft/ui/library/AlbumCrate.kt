package de.kleinabersmart.earloft.ui.library

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.ui.components.LocalOfflineController
import de.kleinabersmart.earloft.ui.components.LocalScrollToTop
import de.kleinabersmart.earloft.ui.components.MoreButton
import de.kleinabersmart.earloft.ui.components.OfflineBadge
import de.kleinabersmart.earloft.ui.components.PlayPauseButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/** Sleeves behind the front one whose top edges show. */
private const val BEHIND = 4

/** Each sleeve further back is this much smaller ... */
private const val DEPTH_SCALE = 0.04f

/** ... and its top edge this much higher than the one in front of it. */
private val EDGE_STEP = 11.dp

/** Drag distance over the crate for one album. */
private val DRAG_STEP = 64.dp

/** How far the record slides out of the front sleeve, as a share of the sleeve width. */
private const val DISC_OUT = 0.42f

/** Further than this, "back to the start" jumps instead of flipping through everything. */
private const val GLIDE_MAX = 8

private val MAX_SLEEVE = 480.dp
private val MIN_SLEEVE = 96.dp
private val SLIDER_WIDTH = 40.dp
private val THUMB_HEIGHT = 48.dp
private val BUBBLE_SIZE = 56.dp
private val VINYL = Color(0xFF141414)

/**
 * Albums as a record crate, the [de.kleinabersmart.earloft.core.model.AlbumView.CRATE] of the [AlbumBrowser]: only the front
 * album as a large sleeve, the top edges of the next ones behind it. Dragging vertically over the crate flips through it (up =
 * on), with momentum, settling on one album; the slider on the right jumps. After settling, the record slides out of the sleeve
 * (label in the cover color). Below: title, artist, year and songs, position, play and the album menu.
 *
 * [label]: the bubble of the slider for an album (initial letter or year); without one the position ("#37").
 * [resetKey]: sort and filter; if they change, the crate shows its first album. Otherwise the front album stays in front when
 * the list changes (unless the first one was in front: then the new first one is), and across the end of the process.
 */
@Composable
internal fun AlbumCrate(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    label: ((Album) -> String)?,
    header: (@Composable () -> Unit)?,
    resetKey: Any?,
    playingAlbumId: Long?,
    onPlay: (Long) -> Unit,
    seedColor: (Long) -> Flow<Int?>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragStepPx = with(density) { DRAG_STEP.toPx() }
    val lastIndex = (albums.size - 1).coerceAtLeast(0)
    val currentAlbums by rememberUpdatedState(albums)

    var frontId by rememberSaveable { mutableStateOf<Long?>(null) }
    val position = remember { Animatable(albums.indexOfFirst { it.id == frontId }.coerceAtLeast(0).toFloat()) }
    // Dragging the crate or the slider; until the crate has settled again, the record stays in its sleeve.
    var touching by remember { mutableStateOf(false) }
    var sliding by remember { mutableStateOf(false) }
    val frontIndex by remember { derivedStateOf { position.value.roundToInt().coerceIn(0, lastIndex) } }
    val settled by remember { derivedStateOf { !touching && !sliding && !position.isRunning && position.value == frontIndex.toFloat() } }

    // A new sort or filter starts at the first album; otherwise the front album stays in front (see above).
    val lastReset = remember { LastResetKey(resetKey) }
    LaunchedEffect(albums, resetKey) {
        val last = (albums.size - 1).coerceAtLeast(0)
        val target = when {
            lastReset.key != resetKey -> 0
            position.value == 0f -> 0
            else -> albums.indexOfFirst { it.id == frontId }.takeIf { it >= 0 } ?: position.value.roundToInt().coerceAtMost(last)
        }
        lastReset.key = resetKey
        if (position.value != target.toFloat()) position.snapTo(target.toFloat())
        frontId = albums.getOrNull(target)?.id
    }
    LaunchedEffect(Unit) {
        snapshotFlow { position.value.roundToInt() }.distinctUntilChanged().drop(1).collect { index ->
            frontId = currentAlbums.getOrNull(index)?.id
            // A light tick per album while the user flips; not for jumps after a sort or a new list.
            if (touching || sliding || position.isRunning) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    // Tapping the tab again: back to the first album.
    val scrollToTop = LocalScrollToTop.current
    LaunchedEffect(scrollToTop) {
        scrollToTop.collect { if (position.value > GLIDE_MAX) position.snapTo(0f) else position.animateTo(0f, settleSpring()) }
    }

    val goTo: (Int) -> Unit = { index -> scope.launch { position.animateTo(index.coerceIn(0, lastIndex).toFloat(), settleSpring()) } }
    val jumpTo: (Int) -> Unit = { index -> scope.launch { position.snapTo(index.coerceIn(0, lastIndex).toFloat()) } }

    Column(modifier.fillMaxSize()) {
        if (header != null) Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)) { header() }
        val front = albums.getOrNull(frontIndex) ?: return@Column
        Row(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch { position.snapTo((position.value - delta / dragStepPx).coerceIn(0f, lastIndex.toFloat())) }
                        },
                        onDragStarted = { touching = true },
                        onDragStopped = { velocity ->
                            touching = false
                            val perSecond = -velocity / dragStepPx
                            val target = exponentialDecay<Float>(frictionMultiplier = 2.5f)
                                .calculateTargetValue(position.value, perSecond)
                                .roundToInt()
                                .coerceIn(0, lastIndex)
                            position.animateTo(target.toFloat(), settleSpring(), initialVelocity = perSecond)
                        },
                    ),
            ) {
                // Landscape (or a wide pane): the details beside the crate instead of below it.
                val wide = maxWidth > maxHeight * 1.3f
                if (wide) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        CrateStack(albums, position, settled, frontIndex, onAlbumClick, goTo, seedColor, Modifier.weight(1f).fillMaxHeight())
                        CrateDetails(
                            front, frontIndex, albums.size, front.id == playingAlbumId, { onPlay(front.id) },
                            Modifier.weight(1f).padding(end = 8.dp),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        CrateStack(albums, position, settled, frontIndex, onAlbumClick, goTo, seedColor, Modifier.weight(1f).fillMaxWidth())
                        CrateDetails(
                            front, frontIndex, albums.size, front.id == playingAlbumId, { onPlay(front.id) },
                            Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                    }
                }
            }
            // With a single album there is nothing to jump to.
            if (albums.size > 1) CrateSlider(
                count = albums.size,
                position = { position.value },
                frontIndex = frontIndex,
                frontTitle = front.title,
                labelAt = { index -> albums.getOrNull(index)?.let { label?.invoke(it) } ?: "#${index + 1}" },
                onSlide = { sliding = it },
                onJump = jumpTo,
                modifier = Modifier.width(SLIDER_WIDTH).fillMaxHeight().padding(vertical = 12.dp),
            )
        }
    }
}

/** The front sleeve and the edges of the sleeves behind it, laid out for [position] (fractional while flipping). */
@Composable
private fun CrateStack(
    albums: List<Album>,
    position: Animatable<Float, *>,
    settled: Boolean,
    frontIndex: Int,
    onAlbumClick: (Long) -> Unit,
    goTo: (Int) -> Unit,
    seedColor: (Long) -> Flow<Int?>,
    modifier: Modifier = Modifier,
) {
    val tilt = remember { ValueAnimator.areAnimatorsEnabled() }
    val shape = MaterialTheme.shapes.medium
    val offline = LocalOfflineController.current
    // The record of the settled album comes out of its sleeve, labeled in the cover color.
    val discAlbumId = albums.getOrNull(frontIndex)?.id?.takeIf { settled }
    val seed by produceState<Int?>(null, discAlbumId) {
        value = null
        discAlbumId?.let { id -> seedColor(id).collect { value = it } }
    }
    val discOut by animateFloatAsState(
        targetValue = if (discAlbumId != null && tilt) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "discOut",
    )
    val labelColor by animateColorAsState(seed?.let { Color(it) } ?: MaterialTheme.colorScheme.primaryContainer, label = "discLabel")
    val first by remember { derivedStateOf { floor(position.value).toInt().coerceAtLeast(0) } }

    BoxWithConstraints(modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
        // The sleeve, the record beside it and the edges above it must fit.
        val rise = EDGE_STEP * BEHIND
        val sleeve = min(min(maxWidth / (1f + DISC_OUT), (maxHeight - rise) / (1f + DEPTH_SCALE * BEHIND)), MAX_SLEEVE)
            .coerceAtLeast(MIN_SLEEVE)
        val density = LocalDensity.current
        val sleevePx = with(density) { sleeve.toPx() }
        val edgePx = with(density) { EDGE_STEP.toPx() }
        val shiftPx = sleevePx * DISC_OUT / 2

        // Deepest first, so the front sleeve is drawn last; the one flipped forward (depth < 0) on top of all.
        val last = minOf(albums.lastIndex, first + BEHIND + 1)
        for (index in last downTo first) {
            val album = albums[index]
            key(album.id) {
                val isFront = index == frontIndex && settled
                val depth = { index - position.value }
                var menu by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .size(sleeve)
                        .graphicsLayer {
                            val d = depth()
                            translationX = -shiftPx * discOut
                            if (d >= 0f) {
                                val scale = 1f - DEPTH_SCALE * d
                                scaleX = scale
                                scaleY = scale
                                translationY = -(d * edgePx + size.height * (1f - scale) / 2f)
                                alpha = if (d > BEHIND) (BEHIND + 1 - d).coerceIn(0f, 1f) else 1f
                            } else {
                                // Flipped forward, toward the viewer, around its bottom edge.
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                if (tilt) {
                                    rotationX = d * 75f
                                    cameraDistance = 12f * density.density
                                }
                                alpha = (1f + d * 1.5f).coerceIn(0f, 1f)
                            }
                            shadowElevation = 6.dp.toPx()
                            this.shape = shape
                        }
                        .then(
                            when {
                                isFront -> Modifier
                                    .combinedClickable(
                                        onClickLabel = stringResource(R.string.album_crate_open),
                                        onClick = { onAlbumClick(album.id) },
                                        onLongClickLabel = stringResource(R.string.action_more),
                                        onLongClick = { menu = true },
                                    )
                                    .semantics { contentDescription = "${album.title}, ${album.artist}" }
                                // The visible edges of the sleeves behind: a tap flips there.
                                index > frontIndex -> Modifier.clickable { goTo(index) }.clearAndSetSemantics {}
                                else -> Modifier.clearAndSetSemantics {}
                            },
                        ),
                ) {
                    if (menu) AlbumMenu(album, onDismiss = { menu = false })
                    if (index == frontIndex && discOut > 0f) {
                        Record(labelColor, Modifier.fillMaxSize().graphicsLayer { translationX = sleevePx * DISC_OUT * discOut })
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .drawWithContent {
                                drawContent()
                                // Further back = darker, as in the shadow of the front sleeves.
                                val d = depth()
                                if (d > 0f) drawRect(Color.Black, alpha = (d * 0.14f).coerceAtMost(0.55f))
                            },
                    ) {
                        AlbumArt(albumId = album.id, modifier = Modifier.fillMaxSize(), shape = shape)
                        if (isFront) OfflineBadge(offline?.albumMarkOf(album.id), Modifier.align(Alignment.BottomStart).padding(8.dp))
                    }
                }
            }
        }
    }
}

/** A vinyl record seen from above: grooves, a little sheen, the label in [labelColor] and the spindle hole. */
@Composable
private fun Record(labelColor: Color, modifier: Modifier = Modifier) {
    val hole = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val radius = size.minDimension / 2f * 0.97f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(VINYL, radius, center)
        val grooves = 14
        for (i in 0 until grooves) {
            val r = radius * (0.42f + 0.54f * i / grooves)
            drawCircle(Color.White.copy(alpha = 0.045f), r, center, style = Stroke(width = 1f))
        }
        val sheen = Stroke(width = radius * 0.5f)
        val arcSize = androidx.compose.ui.geometry.Size(radius * 1.45f, radius * 1.45f)
        val arcTopLeft = Offset(center.x - arcSize.width / 2f, center.y - arcSize.height / 2f)
        drawArc(Color.White.copy(alpha = 0.04f), -60f, 35f, false, arcTopLeft, arcSize, style = sheen)
        drawArc(Color.White.copy(alpha = 0.04f), 120f, 35f, false, arcTopLeft, arcSize, style = sheen)
        drawCircle(labelColor, radius * 0.36f, center)
        drawCircle(Color.Black.copy(alpha = 0.12f), radius * 0.36f, center, style = Stroke(width = radius * 0.02f))
        drawCircle(hole, radius * 0.03f, center)
    }
}

/** Title, artist, year and songs, position, play/pause and ⋮ of the front album. */
@Composable
private fun CrateDetails(
    album: Album,
    index: Int,
    count: Int,
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            album.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val songs = pluralStringResource(R.plurals.song_count, album.songCount, album.songCount)
        val position = stringResource(R.string.album_crate_position, index + 1, count)
        Text(
            listOfNotNull(album.year?.toString(), songs, position).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseButton(playing, onPlay, Modifier.size(48.dp))
            Box {
                MoreButton { menu = true }
                if (menu) AlbumMenu(album, onDismiss = { menu = false })
            }
        }
    }
}

/**
 * Vertical slider on the right edge, always visible: dragging or tapping jumps to the album at that height (coarse; flipping the
 * crate is the fine control). While dragging, a bubble shows [labelAt]. For TalkBack an adjustable range, one album per step.
 */
@Composable
private fun CrateSlider(
    count: Int,
    position: () -> Float,
    frontIndex: Int,
    frontTitle: String,
    labelAt: (Int) -> String,
    onSlide: (Boolean) -> Unit,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = (count - 1).coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableStateOf(0) }
    val currentLast by rememberUpdatedState(last)
    val currentJump by rememberUpdatedState(onJump)
    val currentSlide by rememberUpdatedState(onSlide)
    val state = stringResource(R.string.album_crate_state, frontIndex + 1, count, frontTitle)
    val title = stringResource(R.string.library_albums)

    BoxWithConstraints(
        modifier.semantics {
            contentDescription = title
            stateDescription = state
            progressBarRangeInfo = ProgressBarRangeInfo(frontIndex.toFloat(), 0f..last.toFloat(), steps = (last - 1).coerceAtLeast(0))
            setProgress { value ->
                currentJump(value.roundToInt())
                true
            }
        },
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val rangePx = (trackPx - thumbPx).coerceAtLeast(1f)
        val indexAt = { y: Float -> (((y - thumbPx / 2) / rangePx).coerceIn(0f, 1f) * currentLast).roundToInt() }
        val thumbTop = { if (last > 0) ((position() / last).coerceIn(0f, 1f) * rangePx).roundToInt() else 0 }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            currentSlide(true)
                            dragIndex = indexAt(offset.y)
                            currentJump(dragIndex)
                        },
                        onDragEnd = {
                            dragging = false
                            currentSlide(false)
                        },
                        onDragCancel = {
                            dragging = false
                            currentSlide(false)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val index = indexAt(change.position.y)
                            if (index != dragIndex) {
                                dragIndex = index
                                currentJump(index)
                            }
                        },
                    )
                }
                .pointerInput(Unit) { detectTapGestures { offset -> currentJump(indexAt(offset.y)) } },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp)),
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, thumbTop()) }
                    .size(width = 8.dp, height = THUMB_HEIGHT)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
        }
        // The bubble sits left of the slider, outside its bounds (the crate's Row doesn't clip).
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentSize(Alignment.TopEnd, unbounded = true)
                .offset { IntOffset(-with(density) { (SLIDER_WIDTH + 4.dp).roundToPx() }, thumbTop() + ((thumbPx - with(density) { BUBBLE_SIZE.toPx() }) / 2).roundToInt()) },
        ) {
            Box(
                Modifier
                    .height(BUBBLE_SIZE)
                    .widthIn(min = BUBBLE_SIZE)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelAt(dragIndex.coerceIn(0, last)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private fun settleSpring() = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The sort and filter at the last list change (see [AlbumCrate]). */
private class LastResetKey(var key: Any?)
