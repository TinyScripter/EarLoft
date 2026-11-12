package de.kleinabersmart.earloft.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.playback.AudioEffectSession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(session: AudioEffectSession) : ViewModel() {
    val sessionId: StateFlow<Int?> = session.sessionId
}

/**
 * Öffnet den Equalizer des Systems bzw. einer Equalizer-App für die Wiedergabe der App (Konzept 7 „Equalizer“). `null`, wenn
 * es keinen gibt, der Dienst nicht läuft oder auf ein anderes Gerät gespielt wird ([isRemote]: dort wirkt er nicht). Außerhalb
 * des Menüs aufrufen: Der Starter muss das Schließen des Menüs überleben.
 */
@Composable
fun rememberEqualizerLauncher(isRemote: Boolean, viewModel: EqualizerViewModel = hiltViewModel()): (() -> Unit)? {
    val context = LocalContext.current
    val sessionId by viewModel.sessionId.collectAsStateWithLifecycle()
    // Mit Ergebnis starten: Nur so erfährt der Equalizer, welche App ihn öffnet.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val intent = remember(sessionId) {
        sessionId?.let { id ->
            AudioEffectSession.controlPanelIntent(context, id).takeIf { context.packageManager.resolveActivity(it, 0) != null }
        }
    }
    if (isRemote || intent == null) return null
    return { launcher.launch(intent) }
}
