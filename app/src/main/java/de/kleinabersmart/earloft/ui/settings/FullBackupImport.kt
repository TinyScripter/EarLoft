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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars

/**
 * „Sicherung wiederherstellen“ (Einstellungen → Allgemein und Einrichtungsassistent): Datei wählen, nachfragen (die
 * Einstellungen werden ersetzt), einspielen. Liefert den Aufruf, der die Dateiauswahl öffnet. Fehler meldet
 * [FullBackupMessagesEffect], das Ergebnis `RestoreNoticeEffect` an der Wurzel.
 */
@Composable
fun rememberFullBackupImport(viewModel: FullBackupViewModel): () -> Unit {
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
    return { launcher.launch(arrayOf("*/*")) }
}

/** Die Meldungen von [viewModel] (Sicherung gespeichert bzw. gescheitert) als Snackbar. */
@Composable
fun FullBackupMessagesEffect(viewModel: FullBackupViewModel) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(fullBackupMessage(resources, it), SnackbarDuration.Long) }
    }
}
