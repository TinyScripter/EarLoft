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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
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
 * A tap on the tab that is already selected takes it back to its start page and would drop a half-filled form on it without
 * asking (review5 P43). An open form with unsaved input registers here; the navigation bar then asks it first ([intercept]),
 * and the form calls [resetTab] once the user has chosen to discard.
 */
class TabResetGuard(private val resetTab: () -> Unit = {}) {

    private val askers = mutableListOf<() -> Unit>()

    /** [ask] shows the form's discard question; until [unregister] a tap on the current tab goes there. */
    fun register(ask: () -> Unit) {
        askers += ask
    }

    fun unregister(ask: () -> Unit) {
        askers -= ask
    }

    /** Tap on the current tab: `true` if a form took it over to ask first, the tab then stays as it is for now. */
    fun intercept(): Boolean {
        val ask = askers.lastOrNull() ?: return false
        ask()
        return true
    }

    /** The user discarded the form after [intercept]: the tab goes back to its start page after all. */
    fun proceed() = resetTab()
}

val LocalTabResetGuard = staticCompositionLocalOf { TabResetGuard() }

/**
 * Leaving a form with unsaved input ([dirty]): Back, the back gesture, a tap on the current tab and the returned function ask
 * first whether to discard it; without changes they close right away ([onClose]). Discarding after the tab tap takes the tab
 * to its start page, as the tap would have done.
 */
@Composable
fun rememberDiscardGuard(dirty: Boolean, onClose: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    var askedByTab by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dirty) { asking = true }
    val tabGuard = LocalTabResetGuard.current
    DisposableEffect(tabGuard, dirty) {
        val ask = {
            askedByTab = true
            asking = true
        }
        if (dirty) tabGuard.register(ask)
        onDispose { tabGuard.unregister(ask) }
    }
    if (asking) {
        val dismiss = {
            asking = false
            askedByTab = false
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.form_discard_title)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    val toStart = askedByTab
                    dismiss()
                    if (toStart) tabGuard.proceed() else onClose()
                }) { Text(stringResource(R.string.form_discard)) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.form_keep_editing)) } },
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
