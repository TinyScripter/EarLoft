package de.kleinabersmart.earloft

import android.app.Activity
import android.os.Bundle

/**
 * Receives the system voice search ("Play … with EarLoft", `MEDIA_PLAY_FROM_SEARCH`) and passes it on to [MainActivity] like
 * [ShareReceiverActivity], with a launch ID: with the app closed the search becomes the root intent of the app's task, and after an
 * app update the next start from the launcher would play it again ([HandledLaunches]).
 */
class VoiceSearchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToMainActivity()
    }
}
