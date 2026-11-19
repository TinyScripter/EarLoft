package de.kleinabersmart.earloft

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives shared podcast addresses and `feed://` links (concept 13) and passes them on to the running [MainActivity].
 * Sent directly to MainActivity, the share menu would start a second instance in its own task (seen on the emulator:
 * two EarLoft tasks side by side). With our own flags instead of the caller's, the intent arrives via `onNewIntent`.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(intent)
                .setClass(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
