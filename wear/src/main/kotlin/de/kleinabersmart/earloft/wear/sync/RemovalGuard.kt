package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protects the watch from a phone that suddenly reports (almost) nothing (review5 P1). A reinstalled phone app, or one whose
 * database was created anew, starts empty; a server that answers with an empty library looks the same. The watch would delete
 * its servers with their passwords, its downloads, episodes and books along with their progress. Such a state is therefore
 * held until the user confirms it on the watch ([confirm]) or [HOLD_MS] have passed.
 *
 * A drop is: the server list falling to nothing, more than half of the selections or of the unfinished episodes and books
 * gone at once, or any removal within [HOLD_MS] after the phone's database changed ([notePhoneDatabase]).
 */
@Singleton
class RemovalGuard @Inject constructor(
    @ApplicationContext context: Context,
    private val recheck: PhoneSyncRecheck,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _held = MutableStateFlow(prefs.getLong(KEY_HELD_SINCE, 0) > 0)

    /** A state from the phone is held; the start screen asks whether to delete here too. */
    val held: StateFlow<Boolean> = _held.asStateFlow()

    /**
     * Remembers the database [id] the phone reports. @return whether the phone's database changed within the last [HOLD_MS] (not
     * on the first report: a watch that knew no ID before has nothing to compare).
     */
    fun notePhoneDatabase(id: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (id != null) {
            val previous = prefs.getString(KEY_DATABASE, null)
            if (previous != id) {
                if (previous != null) Log.i(TAG, "The phone's database is new")
                prefs.edit {
                    putString(KEY_DATABASE, id)
                    if (previous != null) putLong(KEY_NEW_SINCE, now)
                }
            }
        }
        val since = prefs.getLong(KEY_NEW_SINCE, 0)
        return since > 0 && now - since < HOLD_MS
    }

    /** May a state that [drops] (see [drops]) be applied? If not, it is held, and the watch looks again when the hold ends. */
    fun allows(drops: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val since = prefs.getLong(KEY_HELD_SINCE, 0)
        if (!drops || prefs.getBoolean(KEY_CONFIRMED, false) || (since > 0 && now - since >= HOLD_MS)) {
            if (since > 0) prefs.edit { remove(KEY_HELD_SINCE); remove(KEY_CONFIRMED) }
            _held.value = false
            return true
        }
        val heldSince = if (since > 0) since else now.also { prefs.edit { putLong(KEY_HELD_SINCE, it) } }
        if (since == 0L) {
            Log.w(TAG, "The phone removed most of what is on the watch: held until confirmed or for a day")
            recheck.recheckIn(heldSince + HOLD_MS - now)
        }
        _held.value = true
        return false
    }

    /** The user wants the phone's state here too; the caller lets the sync run again. */
    fun confirm() {
        prefs.edit { putBoolean(KEY_CONFIRMED, true) }
    }

    companion object {
        private const val TAG = "RemovalGuard"
        private const val PREFS = "removal_guard"
        private const val KEY_DATABASE = "phone_database"
        private const val KEY_NEW_SINCE = "phone_database_new_since"
        private const val KEY_HELD_SINCE = "held_since"
        private const val KEY_CONFIRMED = "confirmed"

        const val HOLD_MS = 24 * 60 * 60_000L

        /**
         * Whether keeping [kept] of [known] entries is a drop: any removal from a [newPhone], otherwise all of them (from
         * [atLeast] on) or more than half (from twice that on). Single removals one after another are never one.
         */
        fun drops(known: Int, kept: Int, newPhone: Boolean, atLeast: Int): Boolean = when {
            kept >= known -> false
            newPhone -> true
            known < atLeast -> false
            kept == 0 -> true
            else -> known >= 2 * atLeast && kept * 2 < known
        }
    }
}

/** Lets the watch apply the phone's state again after [delayMs] (when a held state applies by itself). */
fun interface PhoneSyncRecheck {
    fun recheckIn(delayMs: Long)
}
