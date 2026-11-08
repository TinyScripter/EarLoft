package de.kleinabersmart.tinyamp

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Nimmt geteilte Podcast-Adressen und `feed://`-Links an (Konzept 13) und reicht sie an die laufende [MainActivity] weiter.
 * Direkt an der MainActivity startete das Teilen-Menü eine zweite Instanz in einer eigenen Aufgabe (am Emulator gesehen:
 * zwei TinyAmp-Aufgaben nebeneinander). Mit eigenen Flags statt denen des Aufrufers kommt der Intent über `onNewIntent` an.
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
