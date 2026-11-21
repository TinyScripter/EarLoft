package de.kleinabersmart.earloft

import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * For exported activities, which any app can start with arbitrary extras ([MainActivity], also via
 * [ShareReceiverActivity], which copies them). Below API 33, reading a single extra unpacks all of them, and a Serializable
 * or Parcelable of a class we don't know then throws and crashes the start. Such an intent keeps its action and data but
 * loses its extras.
 */
internal fun Intent.dropUnreadableExtras() {
    try {
        // A copy: on success the intent's own extras stay packed and are unpacked again on first use.
        extras?.isEmpty
    } catch (e: RuntimeException) {
        Log.w("IntentExtras", "Dropping unreadable extras of $action", e)
        replaceExtras(null as Bundle?)
    }
}
