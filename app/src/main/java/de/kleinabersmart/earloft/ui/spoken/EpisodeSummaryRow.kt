package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.TrackDownload
import androidx.compose.ui.semantics.selected as selectedSemantics
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Eine Folge im Reiter „Neu“ und auf der Abo-Seite: links ein kleines Bild neben Titel und darunter (kleiner) Abo und Datum
 * bzw. auf der Abo-Seite ([withPodcast] `false`) Folgennummer und Datum, darunter höchstens zwei Zeilen Inhaltsangabe aus den
 * Shownotes, unten links Download und Warteschlange, rechts Abspielen mit der Restzeit ([remainingMs], beim Tempo des Abos;
 * [PlayTimeButton]). Während der Mehrfachauswahl schaltet ein Tipp die Folge dazu oder weg, und die Knöpfe ruhen. [swipe]:
 * Wischgesten ([EpisodeSwipeBox]), nicht während der Auswahl und nicht in einem Pager.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeSummaryRow(
    item: BookWithProgress,
    withPodcast: Boolean,
    withTime: Boolean,
    remainingMs: Long?,
    download: TrackDownload,
    downloadFraction: Float?,
    queued: Boolean,
    playing: Boolean,
    selection: EpisodeSelection,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onToggleQueue: () -> Unit,
    swipe: Boolean = false,
) {
    val book = item.book
    var menu by remember { mutableStateOf(false) }
    val selecting = selection.active
    val selected = book.id in selection
    val summary = remember(book.description, book.title) { descriptionSummary(book.description, book.title) }
    // Deckend, damit beim Wischen die Fläche dahinter nicht durchscheint (`surface` ist auf einer Gruppe deren Farbe).
    EpisodeSwipeBox(item, enabled = swipe && !selecting) { Box {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { if (selecting) selection.toggle(book.id) else onClick() },
                    onLongClick = { if (selecting) selection.toggle(book.id) else menu = true },
                )
                .semantics { if (selecting) selectedSemantics = selected }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectableArt(book.id, selected, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (book.starred) StarredMark(Modifier.padding(end = 4.dp))
                        val source = listOfNotNull(
                            if (withPodcast) book.author.takeIf { it.isNotBlank() } else episodeLabel(book, withSeason = false),
                            book.addedAt.takeIf { it > 0 }?.let { formatEpisodeDay(it, withTime) },
                        ).joinToString(" · ")
                        Text(
                            source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Die Symbole stehen bündig mit dem Bild darüber, die Tippfläche ragt in den Rand.
                Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                    // Dateien vom Gerät sind schon da.
                    if (!book.isLocal) DownloadButton(download, downloadFraction, onDownload, onRemoveDownload)
                    QueueButton(queued, enabled = !selecting, onToggleQueue)
                }
                Spacer(Modifier.weight(1f))
                PlayTimeButton(item, remainingMs, playing, enabled = !selecting, onPlay)
            }
        }
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false }, onSelect = { selection.toggle(book.id) })
    } }
}

/** Ans Ende der Warteschlange bzw. heraus; eine eingereihte Folge zeigt den Haken in der Akzentfarbe (wie ein fertiger Download). */
@Composable
private fun QueueButton(queued: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, enabled = enabled) {
        if (queued) {
            Icon(
                painterResource(DesignR.drawable.ic_playlist_add_check),
                contentDescription = stringResource(R.string.episode_queue_remove),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        } else {
            Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = stringResource(R.string.episode_queue_add))
        }
    }
}

/**
 * Abspielen samt Restzeit als ein Knopf, nur wenig größer als Symbol und Text (die Tippfläche bleibt 48 dp hoch). Die Farbe
 * zeigt den Stand: nie angefangen hell mit der ganzen Dauer, angefangen (oder gerade laufend) kräftig mit „noch …“, zu Ende
 * gehört nur umrandet mit „Gehört“ (ein Tipp spielt sie von vorn). Ohne bekannte Dauer steht nur das Symbol da.
 */
@Composable
private fun PlayTimeButton(item: BookWithProgress, remainingMs: Long?, playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val progress = item.progress
    val finished = progress?.finished == true
    val started = !finished && (progress?.positionMs ?: 0) > 0
    val label = when {
        finished -> stringResource(R.string.spoken_finished)
        remainingMs == null -> null
        started -> stringResource(R.string.book_remaining, formatDuration(remainingMs))
        else -> formatDuration(remainingMs)
    }
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        started || playing -> colors.primary to colors.onPrimary
        finished -> Color.Transparent to colors.onSurfaceVariant
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container.copy(alpha = container.alpha * alpha),
        contentColor = content.copy(alpha = alpha),
        border = if (finished) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = if (label != null) 10.dp else 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(if (playing) DesignR.drawable.ic_pause else DesignR.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.action_pause else R.string.podcast_play_episode),
                modifier = Modifier.size(18.dp),
            )
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** Deckkraft eines ruhenden Knopfs (während der Mehrfachauswahl), wie bei Material. */
private const val DISABLED_ALPHA = 0.38f
