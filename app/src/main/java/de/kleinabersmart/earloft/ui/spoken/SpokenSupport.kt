package de.kleinabersmart.earloft.ui.spoken

import java.util.Locale
import java.util.Date
import java.util.Calendar
import java.util.TimeZone
import java.text.DateFormat
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.toSize
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.navigation.SharedAcrossScreens
import de.kleinabersmart.earloft.ui.components.AppLogo
import de.kleinabersmart.earloft.ui.components.CoverOutlineWidth
import de.kleinabersmart.earloft.ui.components.coverOutlineColor
import de.kleinabersmart.earloft.ui.components.EmptyAreaHint
import de.kleinabersmart.earloft.ui.components.HeaderActions
import de.kleinabersmart.earloft.ui.components.LocalHeaderActions
import de.kleinabersmart.earloft.ui.components.LocalOpenAreaSettings
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * At the very top the app's banner, below it the Music / Podcasts / Books switch above start and library
 * (concept 12.5). It only switches the content of these two tabs; settings, mini player and
 * now-playing view depend on the current item. The magnifier in the header ([onSearch]) searches in the selected area.
 */
@Composable
fun SpokenModeHost(
    music: @Composable () -> Unit,
    podcasts: @Composable () -> Unit,
    books: @Composable () -> Unit,
    /** After switching to another mode; e.g. closes the previous mode's detail pane. */
    onModeChange: () -> Unit,
    onSearch: () -> Unit,
    /** To the setting that hides areas (General → Areas); offered in empty areas ([EmptyAreaHint]). */
    onOpenAreaSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SpokenModeScaffold(onModeChange, onSearch, onOpenAreaSettings, modifier) { mode ->
        when (mode) {
            SpokenMode.MUSIC -> music()
            SpokenMode.PODCASTS -> podcasts()
            SpokenMode.BOOKS -> books()
        }
    }
}

/** Banner and mode switch above the enabled areas, which lie side by side as pages of a pager. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpokenModeScaffold(
    onModeChange: () -> Unit,
    onSearch: () -> Unit,
    onOpenAreaSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenModeViewModel = hiltViewModel(),
    content: @Composable (SpokenMode) -> Unit,
) {
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    // The enabled areas lie side by side as if on rails: tapping the switch and swiping travel there.
    // The pager is only created with the stored area, so it starts on the correct page without travel, and is then
    // authoritative. If an area is enabled or disabled (General → Areas), it is recreated on the shown area.
    val pages = loaded?.areas
    val pagerState = loaded?.let { (initial, areas) ->
        remember(areas) { PagerState(currentPage = areas.indexOf(initial).coerceAtLeast(0)) { areas.size } }
    }
    // Where the pager is heading or would snap to when the finger is lifted; the switch already shows this during travel.
    val mode = pagerState?.let { pages?.getOrNull(it.targetPage) }
    // Until the pager exists, the switch draws the last known areas so the header doesn't jump.
    val fallbackAreas by viewModel.areas.collectAsStateWithLifecycle()
    val areas = pages ?: fallbackAreas
    val scope = rememberCoroutineScope()
    // Area buttons for the header (e.g. the library sorting), one slot per area.
    val headerActions = remember { SpokenMode.entries.associateWith { HeaderActions() } }
    if (pagerState != null && pages != null) {
        val currentOnModeChange by rememberUpdatedState(onModeChange)
        // Saved only where the pager snaps; a swipe that was started and taken back changes nothing.
        // The start page is saved too (DataStore doesn't rewrite identical values): if the stored area was
        // hidden, after enabling another one its first one would apply again instead of the shown one.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collectIndexed { index, page ->
                // If the area was already stored, the change came from outside (below); the page above then stays open.
                val changed = pages[page] != viewModel.loaded.value?.mode
                viewModel.setMode(pages[page])
                if (index > 0 && changed) currentOnModeChange()
            }
        }
        // Area selected from outside (tap on a notification about new items): the pager travels there, unless the finger
        // is currently on it. After a swipe of its own, the stored area is already the shown one.
        LaunchedEffect(pagerState) {
            viewModel.loaded.filterNotNull().map { it.mode }.distinctUntilChanged().collect { stored ->
                val index = pages.indexOf(stored)
                if (index >= 0 && index != pagerState.settledPage && !pagerState.isScrollInProgress) pagerState.scrollToPage(index)
            }
        }
    }
    // On small phones (low or landscape) the header collapses while scrolling; otherwise there would be hardly any space for the lists.
    // By window size like the window classes (concept 8), not by LocalConfiguration (also changes with language, font …).
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    val compactHeight = window.height < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp ||
        (window.width < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp && window.height < SHORT_PHONE_HEIGHT)
    val collapse = remember(compactHeight) { HeaderCollapse(compactHeight) }
    // The banner consumes the status bar; the screens below no longer have their own bar.
    // The status bar inset stays outside the collapsible header, otherwise the content slides under it when collapsing.
    // The header lies as a tinted band (including status bar) over the content, which starts on the normal surface.
    Column(
        modifier
            .fillMaxSize()
            .nestedScroll(collapse),
    ) {
        // Start and library share the header: when switching between them it stays in place, only the content crossfades.
        // The shared frame sits outside the collapsing, so a collapsed header stays collapsed during the transition too.
        // The tinted band and status bar belong to it; otherwise the color behind would fade out and back in with the page.
        SharedAcrossScreens(APP_HEADER_KEY) {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top))
                    .collapsingHeader(collapse),
            ) {
                TopAppBar(
                    windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { AppLogo(Modifier.height(40.dp)) },
                    // As in a single subscription: refresh at the far right, the search to its left.
                    actions = {
                        mode?.let { headerActions.getValue(it).content?.invoke() }
                        IconButton(onClick = onSearch) {
                            Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(searchLabel(mode ?: SpokenMode.MUSIC)))
                        }
                        when (mode) {
                            SpokenMode.MUSIC -> IconButton(onClick = viewModel::refreshMusic) {
                                Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.settings_refresh_music))
                            }
                            SpokenMode.BOOKS -> IconButton(onClick = viewModel::refreshBooks) {
                                Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.spoken_abs_sync))
                            }
                            SpokenMode.PODCASTS -> IconButton(onClick = viewModel::refreshPodcasts) {
                                Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.podcast_refresh))
                            }
                            null -> {}
                        }
                    },
                )
                // The selected area carries the accent color (like the play buttons), the others lie light on the band.
                val segmentColors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // The fill travels with the pager, also while swiping; the buttons themselves stay transparent.
                val pagePosition: () -> Float? = { pagerState?.let { it.currentPage + it.currentPageOffsetFraction } }
                // The buttons themselves are as tall as their touch target and all equally tall (also with large font); this way
                // the fill covers exactly the row. Otherwise there would be an invisible margin of up to 48 dp around each button.
                // With only one enabled area there is nothing to switch; then the row is omitted.
                if (areas.size > 1) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                            .height(IntrinsicSize.Min)
                            .slidingSegmentIndicator(areas.size, pagePosition, segmentColors, SegmentedButtonDefaults.baseShape),
                    ) {
                        val buttonColors = segmentColors.copy(activeContainerColor = Color.Transparent, inactiveContainerColor = Color.Transparent)
                        areas.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = mode == entry,
                                onClick = {
                                    if (pagerState != null && entry != mode) scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                modifier = Modifier.fillMaxHeight().heightIn(min = SegmentMinHeight),
                                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
                                colors = buttonColors,
                                // No check mark: the fill color already shows the selection.
                                icon = {},
                            ) {
                                // The text changes color as far as the fill already lies beneath it. It stays on one line
                                // and with large font rather gets slightly smaller than wrapping.
                                val style = LocalTextStyle.current
                                BasicText(
                                    stringResource(modeLabel(entry)),
                                    style = style,
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(maxFontSize = style.fontSize),
                                    color = {
                                        val covered = pagePosition()?.let { (1f - abs(it - index)).coerceIn(0f, 1f) } ?: 0f
                                        lerp(segmentColors.inactiveContentColor, segmentColors.activeContentColor, covered)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Pull to refresh syncs the area (like the button at the top).
        val onRefresh = when (mode) {
            SpokenMode.MUSIC, null -> viewModel::refreshMusic
            SpokenMode.BOOKS -> viewModel::refreshBooks
            SpokenMode.PODCASTS -> viewModel::refreshPodcasts
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
        ) {
            if (pagerState != null && pages != null) {
                val minFlingVelocity = with(LocalDensity.current) { MinFlingVelocity.toPx() }
                val handoff = remember(pagerState, minFlingVelocity) { ModeFlingHandoff(pagerState, minFlingVelocity) }
                // The key is the area, not the page: after enabling or disabling an area the others keep
                // their state (tab, scroll position).
                // The last enabled area can't be hidden; then an empty area doesn't offer it.
                CompositionLocalProvider(LocalOpenAreaSettings provides onOpenAreaSettings.takeIf { pages.size > 1 }) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { pages[it].ordinal }) { page ->
                        Box(Modifier.fillMaxSize().nestedScroll(handoff)) {
                            CompositionLocalProvider(LocalHeaderActions provides headerActions.getValue(pages[page])) {
                                content(pages[page])
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Transition from the library tabs into the neighboring area: swiping past the first or last tab,
 * the rest of the gesture already drags the area pager along by itself. Compose swallows the fling on release, though;
 * the pager would then only snap by distance. Here, as when swiping directly on the area, the direction
 * of a short, fast swipe counts too.
 */
private class ModeFlingHandoff(private val pager: PagerState, private val minVelocity: Float) : NestedScrollConnection {
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (abs(pager.currentPageOffsetFraction) < 0.001f) return Velocity.Zero
        val page = pager.currentPage + pager.currentPageOffsetFraction
        val target = when {
            available.x < -minVelocity -> ceil(page)
            available.x > minVelocity -> floor(page)
            else -> round(page)
        }.toInt().coerceIn(0, pager.pageCount - 1)
        pager.animateScrollToPage(target)
        return Velocity(available.x, 0f)
    }
}

/**
 * Draws the light surface behind a row of [count] segments and on top the fill of the selected segment at
 * [position] (0 = first segment, fractions in between). The position is read only while drawing, so following along
 * doesn't recompose anything.
 */
private fun Modifier.slidingSegmentIndicator(
    count: Int,
    position: () -> Float?,
    colors: SegmentedButtonColors,
    shape: Shape,
): Modifier =
    drawWithCache {
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val segment = size.width / count
        onDrawBehind {
            drawPath(outline, colors.inactiveContainerColor)
            val at = position() ?: return@onDrawBehind
            val left = if (layoutDirection == LayoutDirection.Ltr) at * segment else (count - 1 - at) * segment
            clipPath(outline) { drawRect(colors.activeContainerColor, Offset(left, 0f), Size(segment, size.height)) }
        }
    }

/** Minimum height of the area buttons: the recommended touch target, here also visible. */
private val SegmentMinHeight = 48.dp

/** From this velocity the direction of a swipe decides, not the distance (as with the pager itself). */
private val MinFlingVelocity = 400.dp

/**
 * State of the collapsible header: [offset] runs from 0 (fully visible) to -[height]. If the content scrolls up,
 * the header disappears first; when scrolling back it comes back first. Without [enabled] everything stays unchanged.
 */
@Stable
private class HeaderCollapse(private val enabled: Boolean) : NestedScrollConnection {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || height <= 0f) return Offset.Zero
        val target = (offset + available.y).coerceIn(-height, 0f)
        val consumed = target - offset
        offset = target
        return Offset(0f, consumed)
    }
}

private fun Modifier.collapsingHeader(state: HeaderCollapse): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    state.height = placeable.height.toFloat()
    val shown = (placeable.height + state.offset).roundToInt().coerceAtLeast(0)
    layout(placeable.width, shown) { placeable.placeRelative(0, state.offset.roundToInt()) }
}

private const val APP_HEADER_KEY = "app-header"

/** Below this the header also collapses in portrait (small phone); not a window class boundary, those only jump at 900 dp. */
private val SHORT_PHONE_HEIGHT = 700.dp

private fun modeLabel(mode: SpokenMode): Int = when (mode) {
    SpokenMode.MUSIC -> R.string.spoken_mode_music
    SpokenMode.PODCASTS -> R.string.spoken_mode_podcasts
    SpokenMode.BOOKS -> R.string.spoken_mode_books
}

/** "Search in music" etc. */
fun searchLabel(mode: SpokenMode): Int = when (mode) {
    SpokenMode.MUSIC -> R.string.search_in_music
    SpokenMode.PODCASTS -> R.string.search_in_podcasts
    SpokenMode.BOOKS -> R.string.search_in_books
}

/**
 * Square book cover; as long as none is loaded (or none exists), the book icon stays visible. A
 * [chapterImage] (image of an episode's current chapter) lies on top; until it is loaded, the cover stays visible.
 */
@Composable
fun BookArt(
    bookId: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    chapterImage: String? = null,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(CoverOutlineWidth, coverOutlineColor(), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_book),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        AsyncImage(
            model = AppUris.bookCover(bookId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (chapterImage != null) {
            AsyncImage(
                model = chapterImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** "5 h 12 min", "12 min", "45 s" – with non-breaking spaces, so a narrow row doesn't wrap in the middle of the duration. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "$totalSeconds s"
    }
}

/** "1:02:03" or "2:03". */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Once at the app root: shows the [BookLauncher]'s messages as snackbars and asks before a book or an
 * episode is streamed over mobile data (if enabled).
 */
@Composable
fun BookLaunchEffect(viewModel: BookLaunchViewModel = hiltViewModel()) {
    val resources = LocalResources.current
    val snackbars = LocalSnackbars.current
    LaunchedEffect(viewModel) {
        viewModel.launcher.events.collect { event ->
            val text = when (event) {
                BookLaunchEvent.Failed -> resources.getString(R.string.spoken_play_failed)
                is BookLaunchEvent.ResumedFromServer ->
                    resources.getString(R.string.spoken_resumed_from_server, formatClock(event.positionMs))
            }
            snackbars.show(text, SnackbarDuration.Long)
        }
    }
    val question by viewModel.launcher.question.collectAsStateWithLifecycle()
    question?.let { request ->
        val close = viewModel.launcher::dismissQuestion
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.spoken_mobile_stream_title)) },
            text = { Text(stringResource(R.string.spoken_mobile_stream_message, request.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.playOverMobile(request, always = false)
                    close()
                }) { Text(stringResource(R.string.spoken_mobile_stream_play)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        viewModel.playOverMobile(request, always = true)
                        close()
                    }) { Text(stringResource(R.string.spoken_mobile_stream_always)) }
                }
            },
        )
    }
}

private val MARKUP = Regex("<[a-zA-Z/!][^>]*>|&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private val TAG = Regex("<[^>]*>")
// The description doesn't show images; `fromHtml` turned them into a placeholder character (U+FFFC, "OBJ").
private val IMAGE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

// Bare addresses in the text; trailing punctuation doesn't belong to them (see [urlEnd]).
private val BARE_URL = Regex("""(?<![\w/.@])(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
// "1:02:03", "12:34", "75:10"; no time of day ("17:00 Uhr"), not part of a longer digit sequence.
private val TIMESTAMP = Regex("""(?<![\d:.,/])(?:(\d{1,2}):)?(\d{1,3}):([0-5]\d)(?![\d:]|\s*Uhr\b)""")

/**
 * Description of a book or episode. If it contains HTML (paragraphs, bold/italic, links, character entities), it is
 * converted; plain text stays unchanged so its line breaks are preserved. Bare addresses become
 * tappable; with [onTimestamp] also timestamps ("12:34") that fit within the episode ([durationMs], 0 = unknown).
 */
@Composable
fun rememberDescription(raw: String, durationMs: Long = 0, onTimestamp: ((Long) -> Unit)? = null): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val jump by rememberUpdatedState(onTimestamp)
    val timestamps = onTimestamp != null
    return remember(raw, linkColor, durationMs, timestamps) {
        formatDescription(
            raw,
            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            durationMs,
            if (timestamps) { ms -> jump?.invoke(ms) } else null,
        )
    }
}

/** Conversion for [rememberDescription]. */
internal fun formatDescription(
    raw: String,
    links: TextLinkStyles? = null,
    durationMs: Long = 0,
    onTimestamp: ((Long) -> Unit)? = null,
): AnnotatedString {
    val text = raw.trim()
    val base = if (!MARKUP.containsMatchIn(text)) {
        AnnotatedString(text)
    } else {
        AnnotatedString.fromHtml(text.replace(IMAGE, ""), linkStyles = links).trimmed()
    }
    return withTextLinks(base, links, durationMs, onTimestamp)
}

/** Links bare addresses and (with [onTimestamp]) timestamps that aren't links yet. */
private fun withTextLinks(
    base: AnnotatedString,
    links: TextLinkStyles?,
    durationMs: Long,
    onTimestamp: ((Long) -> Unit)?,
): AnnotatedString {
    val text = base.text
    val taken = base.getLinkAnnotations(0, text.length).mapTo(mutableListOf()) { it.start until it.end }
    fun free(range: IntRange) = taken.none { it.first <= range.last && range.first <= it.last }
    val urls = BARE_URL.findAll(text).mapNotNull { match ->
        val range = match.range.first until urlEnd(text, match.range)
        range.takeIf { !it.isEmpty() && free(it) }?.also { taken += it }
    }.toList()
    val stamps = if (onTimestamp == null) emptyList() else TIMESTAMP.findAll(text).mapNotNull { match ->
        val ms = timestampMs(match) ?: return@mapNotNull null
        if (durationMs > 0 && ms > durationMs) return@mapNotNull null
        match.range.takeIf(::free)?.let { it to ms }
    }.toList()
    if (urls.isEmpty() && stamps.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        for (range in urls) {
            val url = text.substring(range.first, range.last + 1)
            val target = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
            addLink(LinkAnnotation.Url(target, links), range.first, range.last + 1)
        }
        for ((range, ms) in stamps) {
            addLink(LinkAnnotation.Clickable("t:$ms", links) { onTimestamp?.invoke(ms) }, range.first, range.last + 1)
        }
    }
}

/** End of a bare address without trailing punctuation; a closing parenthesis stays only if the address opens one. */
private fun urlEnd(text: String, range: IntRange): Int {
    var end = range.last + 1
    while (end > range.first) {
        val last = text[end - 1]
        val unbalanced = last == ')' && text.substring(range.first, end).count { it == '(' } < text.substring(range.first, end).count { it == ')' }
        if (last in ".,;:!?'\"»“”" || unbalanced) end-- else break
    }
    return end
}

/** Position of a timestamp in ms; with hours, at most 59 minutes. */
private fun timestampMs(match: MatchResult): Long? {
    val hours = match.groupValues[1].toLongOrNull()
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    if (hours != null && minutes > 59) return null
    return (((hours ?: 0) * 60 + minutes) * 60 + seconds) * 1000
}

/** Description without tags for the search; the character entities stay (cheaper than a conversion per keystroke). */
fun descriptionForSearch(raw: String): String = if ('<' in raw) raw.replace(TAG, " ") else raw

private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}

/**
 * Short date with the abbreviated month in the order and spelling of [locale] ("Oct 31", "31. Okt.", "31 oct."); the
 * year is only included if it isn't the current one ("Oct 31, 2025").
 */
fun formatEpisodeDate(ms: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    fun yearOf(time: Long) = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR)
    val skeleton = if (yearOf(ms) == yearOf(now)) "MMMd" else "yMMMd"
    return android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale).format(Date(ms))
}

/** Like [formatEpisodeDate], with [withTime] followed by the time of day ("Nov 12 · 17:00"). */
fun formatEpisodeDate(ms: Long, withTime: Boolean): String {
    val date = formatEpisodeDate(ms)
    return if (withTime) "$date · ${formatEpisodeTime(ms)}" else date
}

/** Date of the episode in lists in the style of the locale ("Nov 12, 2026", "12.11.2026"), with [withTime] also the time of day. */
fun formatEpisodeDay(ms: Long, withTime: Boolean): String =
    if (withTime) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    } else {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }

private fun formatEpisodeTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * Episodes published on the same calendar day as another episode of the same podcast in [books] (news
 * several times a day): they show the time of day next to the date, otherwise they couldn't be told apart. Books and episodes
 * without a date don't count.
 */
fun episodesSharingDay(books: List<Book>, zone: TimeZone = TimeZone.getDefault()): Set<Long> {
    val calendar = Calendar.getInstance(zone)
    fun dayOf(ms: Long): Int {
        calendar.timeInMillis = ms
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }
    return books.filter { it.kind == BookKind.EPISODE && it.addedAt > 0 }
        .groupBy { it.serverId to dayOf(it.addedAt) }
        .values.filter { it.size > 1 }
        .flatMapTo(mutableSetOf()) { group -> group.map { it.id } }
}

/**
 * Short summary from the show notes: plain text, lines joined into one paragraph, without lines that only
 * repeat the [title] (ARD Sounds: show notes = title); empty if there is none. The display truncates it to its line count.
 */
fun descriptionSummary(raw: String, title: String): String {
    if (raw.isBlank()) return ""
    val text = if ('<' in raw || '&' in raw) AnnotatedString.fromHtml(raw).text else raw
    return summaryOtherThan(text, title)
}

internal fun summaryOtherThan(text: String, title: String): String =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals(title.trim(), ignoreCase = true) }
        .joinToString(" ")
        .replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")
