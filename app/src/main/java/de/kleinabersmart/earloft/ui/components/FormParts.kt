package de.kleinabersmart.earloft.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** How a password or key field shows its text: hidden by default, the eye at its end shows it (server forms). */
fun passwordTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()

/** The eye at the end of a password field. */
@Composable
fun PasswordVisibilityToggle(visible: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            painterResource(if (visible) DesignR.drawable.ic_visibility_off else DesignR.drawable.ic_visibility),
            contentDescription = stringResource(if (visible) R.string.form_hide_password else R.string.form_show_password),
        )
    }
}

/**
 * Leaving a form with unsaved input ([dirty]): Back, the back gesture and the returned function ask first whether to discard
 * it; without changes they close right away ([onClose]).
 */
@Composable
fun rememberDiscardGuard(dirty: Boolean, onClose: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.form_discard_title)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    asking = false
                    onClose()
                }) { Text(stringResource(R.string.form_discard)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.form_keep_editing)) } },
        )
    }
    return { if (dirty) asking = true else onClose() }
}

/** "+ Add …" as a row of a list (servers in the settings and in the setup wizard). */
@Composable
fun AddRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Remove server" at the end of a server's edit form, in red; asks first ([title], [text]). */
@Composable
fun RemoveServerButton(title: String, text: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var asking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { asking = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = modifier,
    ) {
        Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null)
        Text(stringResource(R.string.server_remove), modifier = Modifier.padding(start = 8.dp))
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    asking = false
                    onRemove()
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
