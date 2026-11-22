package de.kleinabersmart.earloft.wear.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.confirmationDialogCurvedText

/**
 * Says briefly that playing didn't start (review H48): before, a tap that failed (phone not reachable, file missing) did
 * nothing at all. [message] `null` = nothing to say; [onDismiss] after it closed by itself or was swiped away.
 */
@Composable
fun PlayFailedDialog(@StringRes message: Int?, onDismiss: () -> Unit) {
    // The text stays while the dialog fades out after [message] went back to `null`.
    val shown = remember { mutableIntStateOf(0) }
    if (message != null) shown.intValue = message
    val text = if (shown.intValue != 0) stringResource(shown.intValue) else ""
    val style = ConfirmationDialogDefaults.curvedTextStyle
    FailureConfirmationDialog(
        visible = message != null,
        onDismissRequest = onDismiss,
        curvedText = { confirmationDialogCurvedText(text, style) },
    )
}
