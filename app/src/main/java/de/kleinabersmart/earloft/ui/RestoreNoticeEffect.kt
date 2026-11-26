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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class RestoreNoticeViewModel @Inject constructor(notice: RestoreNotice) : ViewModel() {
    val reports: Flow<RestoreReport> = notice.reports

    private val _enterRequests = MutableSharedFlow<MissingCredentials>(extraBufferCapacity = 1)

    /** Taps on "Enter", for whichever UI root is current then ([enter]). */
    val enterRequests: Flow<MissingCredentials> = _enterRequests.asSharedFlow()

    /**
     * The snackbar's action: it outlives a rotation (review T31), the navigation of the root that showed it does not. So the tap
     * goes through here, and the current root navigates (review4 8.6 Y5).
     */
    fun enter(missing: MissingCredentials) {
        _enterRequests.tryEmit(missing)
    }
}

/**
 * After a device change (review K9) or a full backup: servers came back without password or API key. Once
 * per restore a snackbar points this out, after a full backup behind its result (otherwise a new hint
 * would replace the other). "Enter" opens the settings of the affected servers ([onEnter]); `null` (in the
 * setup wizard, which lists the servers itself) without an action.
 */
@Composable
fun RestoreNoticeEffect(onEnter: ((MissingCredentials) -> Unit)?, viewModel: RestoreNoticeViewModel = hiltViewModel()) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val enter by rememberUpdatedState(onEnter)
    LaunchedEffect(viewModel) {
        viewModel.enterRequests.collect { missing -> enter?.invoke(missing) }
    }
    LaunchedEffect(viewModel) {
        viewModel.reports.collect { report ->
            val missing = report.missing
            val action = enter?.takeIf { missing.total > 0 }
            snackbars.show(
                SnackbarMessage(
                    text = restoreText(resources, report),
                    actionLabel = action?.let { resources.getString(R.string.restore_missing_credentials_action) },
                    onAction = action?.let { { viewModel.enter(missing) } },
                    // If something is missing, the hint stays until the user taps "Enter" or "Close".
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
