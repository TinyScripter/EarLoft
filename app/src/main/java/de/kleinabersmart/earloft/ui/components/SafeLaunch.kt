package de.kleinabersmart.earloft.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import de.kleinabersmart.earloft.R

/**
 * Like `launch`, but on a device without a matching app (no file picker, no system settings page; review G66) a hint
 * instead of a crash.
 */
fun <I> ActivityResultLauncher<I>.launchOrHint(input: I, context: Context) {
    try {
        launch(input)
    } catch (e: ActivityNotFoundException) {
        noAppHint(context, e)
    }
}

/** Runs [start] (e.g. `startActivity`) and shows the same hint if no app can handle it. */
fun startOrHint(context: Context, start: () -> Unit) {
    try {
        start()
    } catch (e: ActivityNotFoundException) {
        noAppHint(context, e)
    }
}

private fun noAppHint(context: Context, e: ActivityNotFoundException) {
    Log.w("SafeLaunch", "No app for this action", e)
    Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_LONG).show()
}
