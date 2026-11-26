package de.kleinabersmart.earloft

import android.app.Activity
import android.os.Bundle

/**
 * Receives shared podcast addresses and `feed://` links (concept 13) and passes them on to the running [MainActivity].
 * Sent directly to MainActivity, the share menu would start a second instance in its own task (seen on the emulator:
 * two EarLoft tasks side by side). With our own flags instead of the caller's, the intent arrives via `onNewIntent`.
 * The launch ID keeps an app update from carrying it out again ([HandledLaunches]).
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToMainActivity()
    }
}
