package keinabersmart.kleinaberlaut.ui.spoken

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.AppUris
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import keinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

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
    modifier: Modifier = Modifier,
) {
    SpokenModeScaffold(onModeChange, onSearch, modifier) { mode ->
        when (mode) {
            SpokenMode.MUSIC -> music()
            SpokenMode.PODCASTS -> podcasts()
            SpokenMode.BOOKS -> books()
        }
    }
}

/** Banner und Modus-Schalter über einem Inhalt, der den Modus selbst auswertet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpokenModeScaffold(
    onModeChange: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenModeViewModel = hiltViewModel(),
    content: @Composable (SpokenMode) -> Unit,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    // Auf kleinen Handys (niedrig oder quer) klappt der Kopf beim Scrollen weg; sonst bliebe für die Listen kaum Platz.
    val configuration = LocalConfiguration.current
    val compactHeight = configuration.screenHeightDp < 480 ||
        (configuration.screenWidthDp < 600 && configuration.screenHeightDp < 700)
    val collapse = remember(compactHeight) { HeaderCollapse(compactHeight) }
    // Die Statusleiste verbraucht das Banner; die Bildschirme darunter haben keine eigene Leiste mehr.
    // Der Statuszeilen-Inset bleibt außerhalb des einklappbaren Kopfs, sonst rutscht der Inhalt beim Einklappen darunter.
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top))
            .nestedScroll(collapse),
    ) {
        Column(Modifier.collapsingHeader(collapse)) {
            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                title = {
                    Image(
                        painter = painterResource(R.drawable.logo_wide),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.height(40.dp),
                    )
                },
                // Wie im einzelnen Abo: Aktualisieren ganz rechts, die Suche links davon.
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(searchLabel(mode)))
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
                    }
                },
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SpokenMode.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = mode == entry,
                        onClick = {
                            if (entry != mode) {
                                viewModel.setMode(entry)
                                onModeChange()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, SpokenMode.entries.size),
                    ) {
                        Text(stringResource(modeLabel(entry)))
                    }
                }
            }
        }
        // Herunterziehen gleicht den Bereich ab (wie der Knopf oben).
        val onRefresh = when (mode) {
            SpokenMode.MUSIC -> viewModel::refreshMusic
            SpokenMode.BOOKS -> viewModel::refreshBooks
            SpokenMode.PODCASTS -> viewModel::refreshPodcasts
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            content(mode)
        }
    }
}

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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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

/**
 * Beschreibung eines Buchs oder einer Folge. Enthält sie HTML (Absätze, Fett/Kursiv, Links, Zeichencodes), wird es
 * umgesetzt; reiner Text bleibt unverändert, damit seine Zeilenumbrüche erhalten bleiben.
 */
@Composable
fun rememberDescription(raw: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(raw, linkColor) {
        val text = raw.trim()
        if (!MARKUP.containsMatchIn(text)) {
            AnnotatedString(text)
        } else {
            val links = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            AnnotatedString.fromHtml(text, linkStyles = links).trimmed()
        }
    }
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
