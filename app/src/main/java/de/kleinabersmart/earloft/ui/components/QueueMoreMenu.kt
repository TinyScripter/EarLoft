package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * The ⋮ at the top right of every queue's header (music, podcasts, books). "Clear queue" is always in it, after
 * confirmation with [clearMessage]; if there is nothing to clear ([canClear]), the entry is grayed out. [items]: entries before it,
 * `close` closes the menu.
 */
@Composable
fun QueueMoreMenu(
    clearMessage: String,
    canClear: Boolean,
    onClear: () -> Unit,
    items: @Composable (close: () -> Unit) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            items { menu = false }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_clear)) },
                enabled = canClear,
                onClick = {
                    menu = false
                    confirmClear = true
                },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.queue_clear_title)) },
            text = { Text(clearMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.queue_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Title in a queue's header, with [summary] (e.g. count and remaining time) in small type below it, if present. */
@Composable
fun QueueTitle(summary: String?) {
    Column {
        Text(stringResource(R.string.player_queue))
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
