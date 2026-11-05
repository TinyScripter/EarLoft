package de.kleinabersmart.tinyamp.ui.spoken

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
import androidx.compose.ui.unit.dp
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

/**
 * Hinweis, dass ein Podcast nur zur Probe da ist (Konzept 12.10): auf seiner Seite mit „Abonnieren“ ([action]), auf der
 * Seite einer Folge antippbar ([onClick]) als Weg zum Podcast.
 */
@Composable
fun TrialNotice(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(DesignR.drawable.ic_info), contentDescription = null)
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
