package de.kleinabersmart.earloft.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.core.playback.AudioEffectSession

/**
 * Opens the system equalizer or an equalizer app for the app's playback (concept 7 "Equalizer"). `null` if
 * there is none, the service isn't running or playback goes to another device ([isRemote]: it has no effect there). Call it
 * outside the menu: the launcher must survive the menu closing.
 */
@Composable
fun rememberEqualizerLauncher(isRemote: Boolean, viewModel: EqualizerViewModel = hiltViewModel()): (() -> Unit)? {
    val context = LocalContext.current
    val sessionId by viewModel.sessionId.collectAsStateWithLifecycle()
    // Start for result: only that way does the equalizer learn which app opens it.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val intent = remember(sessionId) {
        sessionId?.let { id ->
            AudioEffectSession.controlPanelIntent(context, id).takeIf { context.packageManager.resolveActivity(it, 0) != null }
        }
    }
    if (isRemote || intent == null) return null
    return { launcher.launch(intent) }
}
