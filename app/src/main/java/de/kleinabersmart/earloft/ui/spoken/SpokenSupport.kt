package de.kleinabersmart.earloft.ui.spoken

import java.util.Locale
import java.util.Date
import java.util.Calendar
import java.util.TimeZone
import java.text.DateFormat
import java.text.SimpleDateFormat
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
    // Knöpfe der Bereiche für den Kopf (z. B. die Sortierung der Bibliothek), je Bereich ein Platz.
    val headerActions = remember { SpokenMode.entries.associateWith { HeaderActions() } }
    if (pagerState != null && pages != null) {
        val currentOnModeChange by rememberUpdatedState(onModeChange)
        // Gespeichert wird erst, wo der Pager einrastet; ein angefangenes und zurückgenommenes Wischen ändert nichts.
        // Die Startseite wird auch gespeichert (DataStore schreibt Gleiches nicht neu): War der gespeicherte Bereich
        // ausgeblendet, gilt sonst nach dem Einschalten eines weiteren wieder dessen erster statt des gezeigten.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collectIndexed { index, page ->
                // War der Bereich schon gespeichert, kam der Wechsel von außen (unten); die Seite darüber bleibt dann offen.
                val changed = pages[page] != viewModel.loaded.value?.mode
                viewModel.setMode(pages[page])
                if (index > 0 && changed) currentOnModeChange()
            }
        }
        // Von außen gewählter Bereich (Tipp auf eine Benachrichtigung über Neues): Der Pager fährt dorthin, außer der Finger
        // ist gerade auf ihm. Nach einem eigenen Wischen ist der gespeicherte Bereich schon der gezeigte.
        LaunchedEffect(pagerState) {
            viewModel.loaded.filterNotNull().map { it.mode }.distinctUntilChanged().collect { stored ->
                val index = pages.indexOf(stored)
                if (index >= 0 && index != pagerState.settledPage && !pagerState.isScrollInProgress) pagerState.scrollToPage(index)
            }
        }
    }
    // Auf kleinen Handys (niedrig oder quer) klappt der Kopf beim Scrollen weg; sonst bliebe für die Listen kaum Platz.
    // Nach der Fenstergröße wie die Fensterklassen (Konzept 8), nicht nach LocalConfiguration (ändert sich auch bei Sprache, Schrift …).
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.toSize().toDpSize() }
    val compactHeight = window.height < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp ||
        (window.width < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp && window.height < SHORT_PHONE_HEIGHT)
    val collapse = remember(compactHeight) { HeaderCollapse(compactHeight) }
    // Die Statusleiste verbraucht das Banner; die Bildschirme darunter haben keine eigene Leiste mehr.
    // Der Statuszeilen-Inset bleibt außerhalb des einklappbaren Kopfs, sonst rutscht der Inhalt beim Einklappen darunter.
    // Der Kopf liegt als getöntes Band (samt Statusleiste) über dem Inhalt, der auf der normalen Fläche beginnt.
    Column(
        modifier
            .fillMaxSize()
            .nestedScroll(collapse),
    ) {
        // Start und Bibliothek teilen sich den Kopf: Beim Wechsel zwischen ihnen bleibt er stehen, nur der Inhalt blendet über.
        // Der geteilte Rahmen sitzt außerhalb des Einklappens, damit ein eingeklappter Kopf auch im Übergang eingeklappt bleibt.
        // Band und Statusleiste gehören mit dazu; sonst blendete die Farbe dahinter mit der Seite aus und wieder ein.
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
                    // Wie im einzelnen Abo: Aktualisieren ganz rechts, die Suche links davon.
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

/** Darunter klappt der Kopf auch hochkant weg (kleines Handy); keine Grenze der Fensterklassen, die springen erst bei 900 dp. */
private val SHORT_PHONE_HEIGHT = 700.dp

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

/**
 * Quadratisches Buchcover; solange keines geladen ist (oder keines existiert), bleibt das Buch-Symbol sichtbar. Ein
 * [chapterImage] (Bild des laufenden Kapitels einer Folge) liegt darüber, bis es geladen ist, bleibt das Cover zu sehen.
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

/**
 * Einmal an der Wurzel der App: zeigt die Hinweise des [BookLauncher] als Snackbar und fragt, bevor ein Buch oder eine
 * Folge über mobile Daten gestreamt wird (wenn eingeschaltet).
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
// Bilder zeigt die Beschreibung nicht; `fromHtml` machte aus ihnen ein Platzhalterzeichen (U+FFFC, „OBJ“).
private val IMAGE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

// Nackte Adressen im Text; Satzzeichen am Ende gehören nicht dazu (siehe [urlEnd]).
private val BARE_URL = Regex("""(?<![\w/.@])(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
// „1:02:03“, „12:34“, „75:10“; keine Uhrzeit („17:00 Uhr“), kein Teil einer längeren Zahlenfolge.
private val TIMESTAMP = Regex("""(?<![\d:.,/])(?:(\d{1,2}):)?(\d{1,3}):([0-5]\d)(?![\d:]|\s*Uhr\b)""")

/**
 * Beschreibung eines Buchs oder einer Folge. Enthält sie HTML (Absätze, Fett/Kursiv, Links, Zeichencodes), wird es
 * umgesetzt; reiner Text bleibt unverändert, damit seine Zeilenumbrüche erhalten bleiben. Nackte Adressen werden
 * antippbar; mit [onTimestamp] auch Zeitmarken („12:34“), die in die Folge passen ([durationMs], 0 = unbekannt).
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

/** Umsetzung zu [rememberDescription]. */
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

/** Verlinkt nackte Adressen und (mit [onTimestamp]) Zeitmarken, die noch kein Link sind. */
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

/** Ende einer nackten Adresse ohne Satzzeichen dahinter; eine Klammer zu bleibt nur, wenn die Adresse eine öffnet. */
private fun urlEnd(text: String, range: IntRange): Int {
    var end = range.last + 1
    while (end > range.first) {
        val last = text[end - 1]
        val unbalanced = last == ')' && text.substring(range.first, end).count { it == '(' } < text.substring(range.first, end).count { it == ')' }
        if (last in ".,;:!?'\"»“”" || unbalanced) end-- else break
    }
    return end
}

/** Position einer Zeitmarke in ms; mit Stunden höchstens 59 Minuten. */
private fun timestampMs(match: MatchResult): Long? {
    val hours = match.groupValues[1].toLongOrNull()
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    if (hours != null && minutes > 59) return null
    return (((hours ?: 0) * 60 + minutes) * 60 + seconds) * 1000
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

/** Wie [formatEpisodeDate], mit [withTime] dahinter die Uhrzeit („Nov. 12 · 17:00“). */
fun formatEpisodeDate(ms: Long, withTime: Boolean): String {
    val date = formatEpisodeDate(ms)
    return if (withTime) "$date · ${formatEpisodeTime(ms)}" else date
}

/** Datum der Folge in Listen („12.11.2026“), mit [withTime] auch die Uhrzeit. */
fun formatEpisodeDay(ms: Long, withTime: Boolean): String =
    if (withTime) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    } else {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }

private fun formatEpisodeTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * Folgen, die am selben Kalendertag erschienen sind wie eine andere Folge desselben Podcasts in [books] (Nachrichten
 * mehrmals am Tag): Bei ihnen steht die Uhrzeit beim Datum, sonst wären sie nicht zu unterscheiden. Bücher und Folgen
 * ohne Datum zählen nicht.
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
 * Folgen, deren Titel eine andere Folge desselben Podcasts in [books] auch trägt („tagesschau in 100 Sekunden“): Bei
 * ihnen steht die erste Zeile der Shownotes unter dem Titel.
 */
fun episodesSharingTitle(books: List<Book>): Set<Long> =
    books.filter { it.kind == BookKind.EPISODE }
        .groupBy { it.serverId to it.title.trim().lowercase() }
        .values.filter { it.size > 1 }
        .flatMapTo(mutableSetOf()) { group -> group.map { it.id } }

/**
 * Die erste Zeile der Shownotes als reiner Text, die nicht nur den [title] wiederholt (ARD Sounds: Shownotes = Titel);
 * leer, wenn es keine gibt.
 */
fun firstDescriptionLine(raw: String, title: String): String {
    if (raw.isBlank()) return ""
    val text = if ('<' in raw || '&' in raw) AnnotatedString.fromHtml(raw).text else raw
    return firstLineOtherThan(text, title)
}

/**
 * Kurze Inhaltsangabe aus den Shownotes: reiner Text, Zeilen zu einem Absatz zusammengezogen, ohne Zeilen, die nur den
 * [title] wiederholen; leer, wenn es keine gibt. Die Anzeige kürzt sie auf ihre Zeilenzahl.
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

internal fun firstLineOtherThan(text: String, title: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals(title.trim(), ignoreCase = true) }.orEmpty()
