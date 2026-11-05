package de.kleinabersmart.kleinaberlaut.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.data.backup.MissingCredentials
import de.kleinabersmart.kleinaberlaut.core.data.backup.RestoreNotice
import de.kleinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import de.kleinabersmart.kleinaberlaut.ui.snackbar.SnackbarMessage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class RestoreNoticeViewModel @Inject constructor(notice: RestoreNotice) : ViewModel() {
    val missingCredentials: Flow<MissingCredentials> = notice.missingCredentials
}

/**
 * Nach einem Gerätewechsel (Review K9): Server kamen ohne Passwort bzw. API-Key zurück. Einmal je Zurückspielen weist eine
 * Snackbar darauf hin; „Eingeben“ öffnet die Einstellungen der betroffenen Server ([onEnter]).
 */
@Composable
fun RestoreNoticeEffect(onEnter: (MissingCredentials) -> Unit, viewModel: RestoreNoticeViewModel = hiltViewModel()) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val enter by rememberUpdatedState(onEnter)
    LaunchedEffect(viewModel) {
        viewModel.missingCredentials.collect { missing ->
            snackbars.show(
                SnackbarMessage(
                    text = resources.getQuantityString(R.plurals.restore_missing_credentials, missing.total, missing.total),
                    actionLabel = resources.getString(R.string.restore_missing_credentials_action),
                    onAction = { enter(missing) },
                    // Kommt nur einmal: bleibt, bis der Nutzer „Eingeben“ oder „Schließen“ tippt.
                    duration = SnackbarDuration.Indefinite,
                    keep = true,
                ),
            )
        }
    }
}
