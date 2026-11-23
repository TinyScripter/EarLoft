package de.kleinabersmart.earloft.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.components.launchOrHint
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars

/**
 * "Restore backup" (Settings → General and setup wizard): pick a file, confirm (the
 * settings are replaced), restore. Returns the call that opens the file picker. Errors are reported by
 * the view model via the app-wide snackbars, the result by `RestoreNoticeEffect` at the root.
 */
@Composable
fun rememberFullBackupImport(viewModel: FullBackupViewModel): () -> Unit {
    val context = LocalContext.current
    var toImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { toImport = it }
    toImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { toImport = null },
            title = { Text(stringResource(R.string.settings_backup_import_title)) },
            text = { Text(stringResource(R.string.settings_backup_import_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.import(uri)
                        toImport = null
                    },
                ) { Text(stringResource(R.string.settings_backup_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toImport = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    return { launcher.launchOrHint(arrayOf("*/*"), context) }
}
