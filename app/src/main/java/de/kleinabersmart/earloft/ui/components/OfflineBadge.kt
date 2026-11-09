package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.designsystem.theme.LocalDarkTheme
import de.kleinabersmart.earloft.core.model.OfflineMark
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Markierung „heruntergeladen“ unten links auf einem Cover (Alben, Bücher, Folgen, Abos): Punkt in der Grundfarbe mit Haken (ganz
 * geladen), mit Kontur statt Füllung (teilweise), grau mit Pfeil (wartet/lädt) oder rot (Fehler). Mit [count] steht die
 * Zahl neben dem Haken (geladene Folgen eines Abos). Ohne [mark] nichts.
 */
@Composable
fun OfflineBadge(mark: OfflineMark?, modifier: Modifier = Modifier, count: Int? = null) {
    if (mark == null) return
    val colors = MaterialTheme.colorScheme
    // Kräftig in beiden Darstellungen: Im Dunklen sind die Container-Farben satt, im Hellen die Grundfarben.
    val dark = LocalDarkTheme.current
    val (container, content) = when (mark) {
        OfflineMark.DONE -> if (dark) colors.primaryContainer to colors.onPrimaryContainer else colors.primary to colors.onPrimary
        OfflineMark.PARTIAL -> colors.surface to colors.primary
        OfflineMark.PENDING -> colors.surface to colors.onSurfaceVariant
        OfflineMark.FAILED -> if (dark) colors.errorContainer to colors.onErrorContainer else colors.error to colors.onError
    }
    val description = when {
        count != null && mark == OfflineMark.DONE -> pluralStringResource(R.plurals.download_state_episodes, count, count)
        else -> stringResource(mark.description)
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (mark == OfflineMark.PARTIAL) BorderStroke(1.5.dp, colors.primary) else null,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.heightIn(min = BadgeSize).padding(horizontal = if (count != null) 6.dp else 3.dp),
        ) {
            Icon(painterResource(mark.icon), contentDescription = null, modifier = Modifier.size(14.dp))
            if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Kleines Symbol in Zeilen (vor der zweiten Zeile oder neben dem Menü); gleiche Bedeutung wie [OfflineBadge]. */
@Composable
fun OfflineMarkIcon(mark: OfflineMark?, modifier: Modifier = Modifier) {
    if (mark == null) return
    val colors = MaterialTheme.colorScheme
    val tint = when (mark) {
        OfflineMark.DONE, OfflineMark.PARTIAL -> colors.primary
        OfflineMark.PENDING -> colors.onSurfaceVariant
        OfflineMark.FAILED -> colors.error
    }
    Icon(
        painterResource(mark.icon),
        contentDescription = stringResource(mark.description),
        tint = tint,
        modifier = modifier.size(16.dp),
    )
}

/** Zweite Zeile einer Listenzeile mit der Markierung davor; ohne [mark] nur der Text. */
@Composable
fun MarkedSupportingText(text: String, mark: OfflineMark?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (mark != null) OfflineMarkIcon(mark, Modifier.padding(end = MarkGap))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Abstand zwischen Zeilen-Markierung und Text. */
val MarkGap = 4.dp

private val BadgeSize = 20.dp

private val OfflineMark.icon: Int
    get() = when (this) {
        OfflineMark.DONE, OfflineMark.PARTIAL -> DesignR.drawable.ic_download_done
        OfflineMark.PENDING -> DesignR.drawable.ic_download
        OfflineMark.FAILED -> DesignR.drawable.ic_error
    }

private val OfflineMark.description: Int
    get() = when (this) {
        OfflineMark.DONE -> R.string.download_state_downloaded
        OfflineMark.PARTIAL -> R.string.download_state_partial
        OfflineMark.PENDING -> R.string.download_state_pending
        OfflineMark.FAILED -> R.string.download_state_failed
    }

@Composable
private fun OfflineBadgesSample() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfflineMark.entries.forEach { OfflineBadge(it) }
                OfflineBadge(OfflineMark.DONE, count = 3)
                OfflineBadge(OfflineMark.DONE, count = 12)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfflineMark.entries.forEach { OfflineMarkIcon(it) }
            }
            // Auf einem bunten Cover muss der Punkt sich abheben.
            Surface(color = Color(0xFFE0A030)) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OfflineMark.entries.forEach { OfflineBadge(it) }
                    OfflineBadge(OfflineMark.DONE, count = 3)
                }
            }
        }
    }
}

@Preview
@Composable
private fun OfflineBadgeLightPreview() {
    EarLoftTheme(darkTheme = false) { OfflineBadgesSample() }
}

@Preview
@Composable
private fun OfflineBadgeDarkPreview() {
    EarLoftTheme(darkTheme = true) { OfflineBadgesSample() }
}
