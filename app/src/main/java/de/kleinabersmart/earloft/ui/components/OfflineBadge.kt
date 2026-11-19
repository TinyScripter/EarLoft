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
 * "Downloaded" mark at the bottom left of a cover (albums, books, episodes, subscriptions): dot in the primary color with a check mark (fully
 * downloaded), outlined instead of filled (partially), gray with an arrow (waiting/downloading) or red (error). With [count] the
 * number is shown next to the check mark (downloaded episodes of a subscription). Nothing without [mark].
 */
@Composable
fun OfflineBadge(mark: OfflineMark?, modifier: Modifier = Modifier, count: Int? = null) {
    if (mark == null) return
    val colors = MaterialTheme.colorScheme
    // Strong in both themes: in dark mode the container colors are saturated, in light mode the primary colors.
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

/** Small icon in rows (before the second line or next to the menu); same meaning as [OfflineBadge]. */
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

/** Second line of a list row with the mark in front; only the text without [mark]. */
@Composable
fun MarkedSupportingText(text: String, mark: OfflineMark?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (mark != null) OfflineMarkIcon(mark, Modifier.padding(end = MarkGap))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Spacing between the row mark and the text. */
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
            // On a colorful cover the dot has to stand out.
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
