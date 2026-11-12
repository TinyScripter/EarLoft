package de.kleinabersmart.earloft.ui

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.backup.MissingCredentials
import de.kleinabersmart.earloft.core.data.backup.RestoreNotice
import de.kleinabersmart.earloft.core.data.backup.RestoreReport
import de.kleinabersmart.earloft.ui.settings.backupRestoredMessage
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class RestoreNoticeViewModel @Inject constructor(notice: RestoreNotice) : ViewModel() {
    val reports: Flow<RestoreReport> = notice.reports
}

/**
 * Nach einem Gerätewechsel (Review K9) oder einer Komplett-Sicherung: Server kamen ohne Passwort bzw. API-Key zurück. Einmal
 * je Wiederherstellen weist eine Snackbar darauf hin, nach einer Komplett-Sicherung hinter ihrem Ergebnis (ein neuer Hinweis
 * ersetzte sonst den anderen). „Eingeben“ öffnet die Einstellungen der betroffenen Server ([onEnter]); `null` (im
 * Einrichtungsassistenten, der die Server selbst listet) ohne Aktion.
 */
@Composable
fun RestoreNoticeEffect(onEnter: ((MissingCredentials) -> Unit)?, viewModel: RestoreNoticeViewModel = hiltViewModel()) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val enter by rememberUpdatedState(onEnter)
    LaunchedEffect(viewModel) {
        viewModel.reports.collect { report ->
            val missing = report.missing
            val action = enter?.takeIf { missing.total > 0 }
            snackbars.show(
                SnackbarMessage(
                    text = restoreText(resources, report),
                    actionLabel = action?.let { resources.getString(R.string.restore_missing_credentials_action) },
                    onAction = action?.let { { it(missing) } },
                    // Fehlt etwas, bleibt der Hinweis, bis der Nutzer „Eingeben“ oder „Schließen“ tippt.
                    duration = if (missing.total > 0) SnackbarDuration.Indefinite else SnackbarDuration.Long,
                    keep = missing.total > 0,
                ),
            )
        }
    }
}

private fun restoreText(resources: Resources, report: RestoreReport): String = listOfNotNull(
    report.backup?.let { backupRestoredMessage(resources, it) },
    report.missing.total.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.restore_missing_credentials, it, it) },
).joinToString(" ")
