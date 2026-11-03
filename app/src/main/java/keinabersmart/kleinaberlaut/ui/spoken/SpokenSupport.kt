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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.AppUris
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import keinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/**
 * Ganz oben das Banner der App, darunter der Schalter Musik / Podcasts / Bücher über Start und Bibliothek
 * (Konzept 12.5). Er schaltet nur den Inhalt dieser beiden Tabs um; Suche, Einstellungen, Mini-Player und
 * Wiedergabe-Ansicht hängen am laufenden Item.
 */
@Composable
fun SpokenModeHost(
    music: @Composable () -> Unit,
    podcasts: @Composable () -> Unit,
    books: @Composable () -> Unit,
    /** Nach dem Wechsel auf einen anderen Modus; schließt z. B. die Detailspalte des vorigen Modus. */
    onModeChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SpokenModeScaffold(onModeChange, modifier) { mode ->
        when (mode) {
            SpokenMode.MUSIC -> music()
            SpokenMode.PODCASTS -> podcasts()
            SpokenMode.BOOKS -> books()
        }
    }
}

/**
 * Banner und Modus-Schalter wie bei [SpokenModeHost], aber mit einem Inhalt, der den Modus selbst auswertet; mit
 * [searchSwitch] sitzt oben rechts der Schalter „Überall suchen“ (für die Suche).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenModeScaffold(
    onModeChange: () -> Unit,
    modifier: Modifier = Modifier,
    searchSwitch: Boolean = false,
    viewModel: SpokenModeViewModel = hiltViewModel(),
    content: @Composable (SpokenMode) -> Unit,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val searchAll by viewModel.searchAll.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    // Die Statusleiste verbraucht das Banner; die Bildschirme darunter haben keine eigene Leiste mehr.
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Image(
                    painter = painterResource(R.drawable.logo_wide),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(40.dp),
                )
            },
            actions = {
                if (searchSwitch) {
                    FilterChip(
                        selected = searchAll,
                        onClick = { viewModel.setSearchAll(!searchAll) },
                        label = { Text(stringResource(R.string.search_hint_all)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                } else if (mode == SpokenMode.MUSIC) {
                    IconButton(onClick = viewModel::refreshMusic) {
                        Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.settings_refresh_music))
                    }
                } else if (mode == SpokenMode.BOOKS) {
                    IconButton(onClick = viewModel::refreshBooks) {
                        Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.spoken_abs_sync))
                    }
                }
                if (!searchSwitch && mode == SpokenMode.PODCASTS) {
                    IconButton(onClick = viewModel::refreshPodcasts) {
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
        // Herunterziehen gleicht den Bereich ab (wie der Knopf oben); die Suche hat das nicht.
        val onRefresh = when {
            searchSwitch -> null
            mode == SpokenMode.MUSIC -> viewModel::refreshMusic
            mode == SpokenMode.BOOKS -> viewModel::refreshBooks
            mode == SpokenMode.PODCASTS -> viewModel::refreshPodcasts
            else -> null
        }
        if (onRefresh != null) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(mode)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { content(mode) }
        }
    }
}

private fun modeLabel(mode: SpokenMode): Int = when (mode) {
    SpokenMode.MUSIC -> R.string.spoken_mode_music
    SpokenMode.PODCASTS -> R.string.spoken_mode_podcasts
    SpokenMode.BOOKS -> R.string.spoken_mode_books
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

/** „5 h 12 min“, „12 min“, „45 s“. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "$totalSeconds s"
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
