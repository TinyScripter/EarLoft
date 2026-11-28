package de.kleinabersmart.earloft

import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * For exported activities, which any app can start with arbitrary extras ([MainActivity], also via
 * [ShareReceiverActivity], which copies them). Below API 33, reading a single extra unpacks all of them, and a Serializable
 * or Parcelable of a class we don't know then throws and crashes the start. Such an intent keeps its action and data but
 * loses its extras.
 *
 * From API 33 a value is only unpacked when it is read itself, so every value is read here: `ComponentActivity` hands all extras to
 * each `SavedStateHandle` of its view models as default arguments, and that reads every value (review5 P39).
 */
internal fun Intent.dropUnreadableExtras() {
    try {
        // A copy: on success the intent's own extras stay packed and are unpacked again on first use.
        extras?.let { copy -> copy.keySet().forEach { @Suppress("DEPRECATION") copy.get(it) } }
    } catch (e: RuntimeException) {
        Log.w("IntentExtras", "Dropping unreadable extras of $action", e)
        replaceExtras(null as Bundle?)
    }
}

/**
 * Reading single extras of a foreign intent: the targeted read of a value that is an unknown Parcelable throws
 * (`BadParcelableException`). Then the extra counts as missing (review2 J71). A second line of defense behind
 * [dropUnreadableExtras], which only reads the top level: a [Bundle] extra may still hold such a value.
 */
internal fun Intent.safeBooleanExtra(name: String): Boolean = readSafely(name) { getBooleanExtra(name, false) } ?: false

internal fun Intent.safeStringExtra(name: String): String? = readSafely(name) { getStringExtra(name) }

internal fun Intent.safeCharSequenceExtra(name: String): CharSequence? = readSafely(name) { getCharSequenceExtra(name) }

/** Read in full right away (`isEmpty` unparcels it): an unknown parcelable in it fails here, not later when reading a value. */
internal fun Intent.safeBundleExtra(name: String): Bundle? = readSafely(name) { getBundleExtra(name)?.also { it.isEmpty } }

private inline fun <T> Intent.readSafely(name: String, read: () -> T): T? = try {
    read()
} catch (e: RuntimeException) {
    Log.w("IntentExtras", "Extra $name of $action not readable", e)
    null
}
