package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Hinweis, dass ein Podcast nur zur Probe da ist (Konzept 12.10): auf seiner Seite mit „Abonnieren“ ([action]), auf der
 * Seite einer Folge antippbar ([onClick]) als Weg zum Podcast. Mit [error] als Fehler gefärbt (kaputter Feed).
 */
@Composable
fun TrialNotice(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(if (error) DesignR.drawable.ic_error else DesignR.drawable.ic_info), contentDescription = null)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
            if (action != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                    action()
                }
            }
        }
    }
}

/** „Abgleich fehlgeschlagen seit 12.11.2026: Feed nicht mehr vorhanden (404)“ für ein Abo mit [PodcastFeed.hasProblem]. */
@Composable
fun feedProblemText(feed: PodcastFeed): String =
    stringResource(
        R.string.podcast_refresh_failing,
        formatEpisodeDay(feed.failingSince, withTime = false),
        stringResource(
            when (feed.refreshError) {
                FeedError.GONE -> R.string.feed_error_gone
                FeedError.DENIED -> R.string.feed_error_denied
                FeedError.SERVER -> R.string.feed_error_server
                FeedError.NOT_A_FEED -> R.string.feed_error_not_a_feed
                FeedError.TLS -> R.string.feed_error_tls
                FeedError.UNREACHABLE, null -> R.string.feed_error_unreachable
            },
        ),
    )
