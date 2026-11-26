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
import de.kleinabersmart.earloft.ui.permission.blockedByHomeNetwork
import de.kleinabersmart.earloft.ui.permission.rememberLocalNetworkGranted
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Notice that a podcast is only there on trial (concept 12.10): on its page with "Subscribe" ([action]), on the
 * page of an episode tappable ([onClick]) as a way to the podcast. With [error] colored as an error (broken feed).
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

/** The feed on the home network fails only because the home network permission is missing (review4 T5). */
@Composable
fun feedBlockedByHomeNetwork(feed: PodcastFeed): Boolean =
    blockedByHomeNetwork(feed.refreshError == FeedError.UNREACHABLE || feed.refreshError == null, feed.url, rememberLocalNetworkGranted())

/** "Sync failing since 12.11.2026: feed no longer exists (404)" for a subscription with [PodcastFeed.hasProblem]. */
@Composable
fun feedProblemText(feed: PodcastFeed): String {
    val reason = when {
        feedBlockedByHomeNetwork(feed) -> R.string.sync_failed_home_network
        else -> when (feed.refreshError) {
            FeedError.GONE -> R.string.feed_error_gone
            FeedError.DENIED -> R.string.feed_error_denied
            FeedError.SERVER -> R.string.feed_error_server
            FeedError.NOT_A_FEED -> R.string.feed_error_not_a_feed
            FeedError.TLS -> R.string.feed_error_tls
            FeedError.UNREACHABLE, null -> R.string.feed_error_unreachable
        }
    }
    return stringResource(R.string.podcast_refresh_failing, formatEpisodeDay(feed.failingSince, withTime = false), stringResource(reason))
}
