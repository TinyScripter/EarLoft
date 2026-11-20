package de.kleinabersmart.earloft.core.data.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second opinion before the sweep of media on the device (review G6). While Android rebuilds its media index (after an
 * update) the MediaStore delivers a valid but empty or incomplete list; the sweep would delete songs with their history and
 * playlist entries, books and episodes with their progress. A drop to nothing or by more than half is therefore only applied
 * when a later scan, at least [CONFIRM_AFTER_MS] after the first, still sees it.
 */
@Singleton
class SweepGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** May [area] sweep now? [known]: entries in the database before the scan, [listed]: entries the scan saw. */
    @Synchronized
    fun allows(area: String, known: Int, listed: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pendingSince = prefs.getLong(area, 0).takeIf { it > 0 }
        val (allowed, next) = decide(known, listed, pendingSince, nowMs)
        if (!allowed) Log.w(TAG, "$area: $listed of $known entries seen, removal postponed")
        if (next != pendingSince) prefs.edit { if (next == null) remove(area) else putLong(area, next) }
        return allowed
    }

    companion object {
        private const val TAG = "SweepGuard"
        private const val PREFS = "sweep_guard"

        /** The index rebuild after an update takes minutes; two scans right after each other confirm nothing. */
        const val CONFIRM_AFTER_MS = 30L * 60 * 1000

        /** Whether to sweep, and the start of a pending drop to remember (`null` = none). */
        internal fun decide(known: Int, listed: Int, pendingSince: Long?, nowMs: Long): Pair<Boolean, Long?> {
            val drops = known > 0 && (listed == 0 || (known - listed) * 2 > known)
            return when {
                !drops -> true to null
                pendingSince != null && nowMs - pendingSince >= CONFIRM_AFTER_MS -> true to null
                else -> false to (pendingSince ?: nowMs)
            }
        }
    }
}
