package de.kleinabersmart.earloft.ui.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.playback.AudioEffectSession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(session: AudioEffectSession) : ViewModel() {
    val sessionId: StateFlow<Int?> = session.sessionId
}
