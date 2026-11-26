package de.kleinabersmart.earloft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.edit
import java.util.UUID

/** One-time ID of a start from outside (share, voice search, notification); see [HandledLaunches]. */
internal const val EXTRA_LAUNCH_ID = "de.kleinabersmart.earloft.LAUNCH_ID"

/** [this] with a new [EXTRA_LAUNCH_ID]; replaces one the caller may have put in. */
internal fun Intent.withLaunchId(): Intent = putExtra(EXTRA_LAUNCH_ID, UUID.randomUUID().toString())

/**
 * Passes the intent of this trampoline on to [MainActivity] with a fresh launch ID and our own flags: in the running app it arrives via
 * `onNewIntent`; otherwise it starts the app's task (sent directly, the caller's flags would open a second instance in its own task).
 * Unreadable extras go first: adding the ID unpacks all of them below API 33, and a foreign class among them would crash here, before
 * [MainActivity] could drop them.
 */
internal fun Activity.forwardToMainActivity() {
    startActivity(intent.forwardedToMainActivity(this))
    finish()
}

/** The copy of [this] that [forwardToMainActivity] sends. */
internal fun Intent.forwardedToMainActivity(context: Context): Intent =
    Intent(this)
        .apply { dropUnreadableExtras() }
        .setClass(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .withLaunchId()

/**
 * Which starts from outside [MainActivity] has already carried out. A start with the app closed makes its intent the root intent of the
 * app's task. An app update ends the process and drops the saved state but keeps the task; opening the app from the launcher then
 * recreates MainActivity with that old intent, without saved state and without `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (seen on the
 * emulator after `adb install -r`, review4 8.4). The system keeps action and data of that intent but not its extras: a shared text
 * came back without text ("no feed address"), a voice search without its query (an empty query resumes playback), a `feed://` link
 * complete. That's why the actions only the trampolines deliver ([TRAMPOLINE_ACTIONS]) need a launch ID; one that still carries its ID
 * runs only once, remembered on disk, because the update ends the process. Notification intents lose their extras the same way and
 * then do nothing (no target, no episode).
 */
internal class HandledLaunches(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * `true` if [intent] is to be carried out: the first time for its [EXTRA_LAUNCH_ID], which is then remembered. Without one, all but
     * the [TRAMPOLINE_ACTIONS] (the launcher, notifications; those come without the ID only as such a replay, or explicitly from another
     * app, which has to go through the trampolines like everyone else).
     */
    fun claim(intent: Intent): Boolean {
        val id = intent.safeStringExtra(EXTRA_LAUNCH_ID)?.takeIf { it.isNotBlank() } ?: return intent.action !in TRAMPOLINE_ACTIONS
        val handled = prefs.getString(KEY_HANDLED, null)?.split(SEPARATOR).orEmpty().filter { it.isNotEmpty() }
        if (id in handled) return false
        prefs.edit { putString(KEY_HANDLED, (handled + id).takeLast(MAX_REMEMBERED).joinToString(SEPARATOR)) }
        return true
    }

    private companion object {
        /** What [ShareReceiverActivity] and [VoiceSearchActivity] deliver, always with a launch ID. */
        val TRAMPOLINE_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_VIEW, MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)

        const val PREFS = "handled_launches"
        const val KEY_HANDLED = "ids"
        const val SEPARATOR = ","

        /** Only the root intent of the one task matters; a few more cover tasks the system still keeps from before. */
        const val MAX_REMEMBERED = 20
    }
}
