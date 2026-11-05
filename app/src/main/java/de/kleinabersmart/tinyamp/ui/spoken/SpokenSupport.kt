package de.kleinabersmart.tinyamp.ui.spoken

import java.util.Locale
import java.util.Date
import java.util.Calendar
import java.text.SimpleDateFormat
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.model.AppUris
import de.kleinabersmart.tinyamp.core.model.SpokenMode
import de.kleinabersmart.tinyamp.navigation.SharedAcrossScreens
import de.kleinabersmart.tinyamp.ui.components.AppLogo
import de.kleinabersmart.tinyamp.ui.components.CoverOutlineWidth
import de.kleinabersmart.tinyamp.ui.components.coverOutlineColor
import de.kleinabersmart.tinyamp.ui.components.EmptyAreaHint
import de.kleinabersmart.tinyamp.ui.components.LocalOpenAreaSettings
import de.kleinabersmart.tinyamp.ui.snackbar.LocalSnackbars
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

/**
 * Ganz oben das Banner der App, darunter der Schalter Musik / Podcasts / Bücher über Start und Bibliothek
 * (Konzept 12.5). Er schaltet nur den Inhalt dieser beiden Tabs um; Einstellungen, Mini-Player und
 * Wiedergabe-Ansicht hängen am laufenden Item. Die Lupe im Kopf ([onSearch]) sucht im gewählten Bereich.
 */
@Composable
fun SpokenModeHost(
    music: @Composable () -> Unit,
    podcasts: @Composable () -> Unit,
    books: @Composable () -> Unit,
    /** Nach dem Wechsel auf einen anderen Modus; schließt z. B. die Detailspalte des vorigen Modus. */
    onModeChange: () -> Unit,
    onSearch: () -> Unit,
    /** Zur Einstellung, die Bereiche ausblendet (Allgemein → Bereiche); angeboten in leeren Bereichen ([EmptyAreaHint]). */
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

/** Banner und Modus-Schalter über den eingeschalteten Bereichen, die als Seiten eines Pagers nebeneinanderliegen. */
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
    // Die eingeschalteten Bereiche liegen nebeneinander wie auf Schienen: Tippen auf den Schalter und Wischen fahren dorthin.
    // Der Pager entsteht erst mit dem gespeicherten Bereich, beginnt also ohne Fahrt auf der richtigen Seite, und ist dann
    // maßgeblich. Wird ein Bereich ein- oder ausgeschaltet (Allgemein → Bereiche), entsteht er neu auf dem gezeigten Bereich.
    val pages = loaded?.areas
    val pagerState = loaded?.let { (initial, areas) ->
        remember(areas) { PagerState(currentPage = areas.indexOf(initial).coerceAtLeast(0)) { areas.size } }
    }
    // Wohin der Pager fährt bzw. bei losgelassenem Finger einrasten würde; der Schalter zeigt das schon während der Fahrt.
    val mode = pagerState?.let { pages?.getOrNull(it.targetPage) }
    // Bis der Pager steht, zeichnet der Schalter die zuletzt bekannten Bereiche, damit der Kopf nicht springt.
    val fallbackAreas by viewModel.areas.collectAsStateWithLifecycle()
    val areas = pages ?: fallbackAreas
    val scope = rememberCoroutineScope()
    if (pagerState != null && pages != null) {
        val currentOnModeChange by rememberUpdatedState(onModeChange)
        // Gespeichert wird erst, wo der Pager einrastet; ein angefangenes und zurückgenommenes Wischen ändert nichts.
        // Die Startseite wird auch gespeichert (DataStore schreibt Gleiches nicht neu): War der gespeicherte Bereich
        // ausgeblendet, gilt sonst nach dem Einschalten eines weiteren wieder dessen erster statt des gezeigten.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collectIndexed { index, page ->
                viewModel.setMode(pages[page])
                if (index > 0) currentOnModeChange()
            }
        }
    }
    // Auf kleinen Handys (niedrig oder quer) klappt der Kopf beim Scrollen weg; sonst bliebe für die Listen kaum Platz.
    val configuration = LocalConfiguration.current
    val compactHeight = configuration.screenHeightDp < 480 ||
        (configuration.screenWidthDp < 600 && configuration.screenHeightDp < 700)
    val collapse = remember(compactHeight) { HeaderCollapse(compactHeight) }
    // Die Statusleiste verbraucht das Banner; die Bildschirme darunter haben keine eigene Leiste mehr.
    // Der Statuszeilen-Inset bleibt außerhalb des einklappbaren Kopfs, sonst rutscht der Inhalt beim Einklappen darunter.
    // Der Kopf liegt als getöntes Band (samt Statusleiste) über dem Inhalt, der auf der normalen Fläche beginnt.
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top))
            .nestedScroll(collapse),
    ) {
        // Start und Bibliothek teilen sich den Kopf: Beim Wechsel zwischen ihnen bleibt er stehen, nur der Inhalt blendet über.
        // Der geteilte Rahmen sitzt außerhalb des Einklappens, damit ein eingeklappter Kopf auch im Übergang eingeklappt bleibt.
        SharedAcrossScreens(APP_HEADER_KEY) {
            Column(Modifier.collapsingHeader(collapse)) {
                TopAppBar(
                    windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { AppLogo(Modifier.height(40.dp)) },
                    // Wie im einzelnen Abo: Aktualisieren ganz rechts, die Suche links davon.
                    actions = {
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
                // Der gewählte Bereich trägt die Akzentfarbe (wie die Play-Knöpfe), die anderen liegen hell auf dem Band.
                val segmentColors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // Die Füllung fährt mit dem Pager mit, auch während des Wischens; die Knöpfe selbst bleiben durchsichtig.
                val pagePosition: () -> Float? = { pagerState?.let { it.currentPage + it.currentPageOffsetFraction } }
                // Die Knöpfe sind selbst so hoch wie ihre Tippfläche und alle gleich hoch (auch bei großer Schrift); so deckt
                // die Füllung genau die Zeile ab. Sonst läge um jeden Knopf ein unsichtbarer Rand bis 48 dp.
                // Mit nur einem eingeschalteten Bereich gibt es nichts umzuschalten; dann entfällt die Zeile.
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
                                // Kein Haken: Die Füllfarbe zeigt die Auswahl schon.
                                icon = {},
                            ) {
                                // Die Schrift wechselt die Farbe so weit, wie die Füllung schon unter ihr liegt. Sie bleibt einzeilig
                                // und wird bei großer Schrift lieber etwas kleiner, als umzubrechen.
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
        // Herunterziehen gleicht den Bereich ab (wie der Knopf oben).
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
                // Schlüssel ist der Bereich, nicht die Seite: Nach dem Ein- oder Ausschalten eines Bereichs behalten die übrigen
                // ihren Zustand (Reiter, Scrollstand).
                // Den letzten eingeschalteten Bereich kann man nicht ausblenden; dann bietet ein leerer Bereich das nicht an.
                CompositionLocalProvider(LocalOpenAreaSettings provides onOpenAreaSettings.takeIf { pages.size > 1 }) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { pages[it].ordinal }) { page ->
                        Box(Modifier.fillMaxSize().nestedScroll(handoff)) {
                            content(pages[page])
                        }
                    }
                }
            }
        }
    }
}

/**
 * Übergang aus den Reitern der Bibliothek in den Bereich daneben: Wischt man über den ersten bzw. letzten Reiter hinaus,
 * zieht der Rest der Geste schon von selbst den Bereichs-Pager mit. Den Schwung beim Loslassen schluckt Compose aber;
 * der Pager rastete dann nur nach der Strecke ein. Hier zählt wie beim Wischen direkt auf dem Bereich auch die Richtung
 * eines kurzen, schnellen Wischers.
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
 * Zeichnet hinter eine Zeile aus [count] Segmenten die helle Fläche und darüber die Füllung des gewählten Segments an
 * [position] (0 = erstes Segment, Bruchteile dazwischen). Gelesen wird die Lage erst beim Zeichnen, damit das Mitfahren
 * nichts neu zusammensetzt.
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

/** Mindesthöhe der Bereichs-Knöpfe: die empfohlene Tippfläche, hier zugleich sichtbar. */
private val SegmentMinHeight = 48.dp

/** Ab dieser Geschwindigkeit entscheidet die Richtung eines Wischers, nicht die Strecke (wie beim Pager selbst). */
private val MinFlingVelocity = 400.dp

/**
 * Zustand des einklappbaren Kopfs: [offset] läuft von 0 (ganz sichtbar) bis -[height]. Scrollt der Inhalt nach oben,
 * verschwindet zuerst der Kopf; beim Zurückscrollen kommt er zuerst wieder. Ohne [enabled] bleibt alles unverändert.
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

private fun modeLabel(mode: SpokenMode): Int = when (mode) {
    SpokenMode.MUSIC -> R.string.spoken_mode_music
    SpokenMode.PODCASTS -> R.string.spoken_mode_podcasts
    SpokenMode.BOOKS -> R.string.spoken_mode_books
}

/** „In Musik suchen“ usw. */
fun searchLabel(mode: SpokenMode): Int = when (mode) {
    SpokenMode.MUSIC -> R.string.search_in_music
    SpokenMode.PODCASTS -> R.string.search_in_podcasts
    SpokenMode.BOOKS -> R.string.search_in_books
}

/** Quadratisches Buchcover; solange keines geladen ist (oder keines existiert), bleibt das Buch-Symbol sichtbar. */
@Composable
fun BookArt(
    bookId: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
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
    }
}

/** „5 h 12 min“, „12 min“, „45 s“ – mit festen Leerzeichen, damit eine schmale Zeile nicht mitten in der Dauer umbricht. */
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

/** „1:02:03“ bzw. „2:03“. */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Einmal an der Wurzel der App: zeigt die Hinweise des [BookLauncher] als Snackbar. */
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
}

private val MARKUP = Regex("<[a-zA-Z/!][^>]*>|&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private val TAG = Regex("<[^>]*>")
// Bilder zeigt die Beschreibung nicht; `fromHtml` machte aus ihnen ein Platzhalterzeichen (U+FFFC, „OBJ“).
private val IMAGE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

/**
 * Beschreibung eines Buchs oder einer Folge. Enthält sie HTML (Absätze, Fett/Kursiv, Links, Zeichencodes), wird es
 * umgesetzt; reiner Text bleibt unverändert, damit seine Zeilenumbrüche erhalten bleiben.
 */
@Composable
fun rememberDescription(raw: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(raw, linkColor) {
        formatDescription(raw, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
    }
}

/** Umsetzung zu [rememberDescription]. */
internal fun formatDescription(raw: String, links: TextLinkStyles? = null): AnnotatedString {
    val text = raw.trim()
    if (!MARKUP.containsMatchIn(text)) return AnnotatedString(text)
    return AnnotatedString.fromHtml(text.replace(IMAGE, ""), linkStyles = links).trimmed()
}

/** Beschreibung ohne Tags für die Suche; die Zeichencodes bleiben stehen (billiger als eine Umsetzung je Tastendruck). */
fun descriptionForSearch(raw: String): String = if ('<' in raw) raw.replace(TAG, " ") else raw

private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}

/** „Okt. 31“; das Jahr steht nur dabei, wenn es nicht das laufende ist („Okt. 31, 2025“). */
fun formatEpisodeDate(ms: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    fun yearOf(time: Long) = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR)
    val pattern = if (yearOf(ms) == yearOf(now)) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, locale).format(Date(ms))
}
