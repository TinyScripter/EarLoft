package de.kleinabersmart.earloft.core.datasource.local

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Remembers which storage volumes (internal storage, SD card, USB drive) held files of an area (music, books, podcasts) and
 * tells whether one of them is missing now (review G6). A scan with an ejected SD card succeeds but lacks the card's files;
 * its sweep would delete them along with history, playlist entries and progress. Such a scan is incomplete until the
 * volume is back, or until it has been missing for [FORGET_AFTER_MS] (then it counts as removed for good).
 *
 * Volume names come from `MediaStore.Audio.Media.VOLUME_NAME` and need Android 10; below that every scan counts as complete.
 */
internal class VolumeWatch(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * `false` if a volume that held files of [area] is not mounted. [withFiles]: the volumes the scan found files on.
     * Remembers the result for the next scan.
     */
    @Synchronized
    @SuppressLint("UseKtx") // core-ktx is not a dependency of this module.
    fun complete(area: String, withFiles: Set<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val mounted = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Mounted volumes not readable", e)
            return true
        }
        val remembered = prefs.getStringSet(area, emptySet()).orEmpty().mapNotNull { entry ->
            val name = entry.substringBeforeLast('|')
            entry.substringAfterLast('|').toLongOrNull()?.let { name to it }
        }.toMap()
        val (complete, next) = evaluate(remembered, withFiles, mounted, nowMs)
        if (!complete) Log.w(TAG, "$area: volume ${remembered.keys - mounted} is missing, nothing is removed")
        prefs.edit().putStringSet(area, next.map { (name, at) -> "$name|$at" }.toSet()).apply()
        return complete
    }

    companion object {
        private const val TAG = "VolumeWatch"
        private const val PREFS = "local_volumes"

        /** A volume missing this long counts as removed: its files go with the next sweep. */
        const val FORGET_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * [remembered]: volume → last time it held files. Returns whether no remembered volume is missing, and what to remember
         * next: missing volumes keep their time (until [FORGET_AFTER_MS]), volumes with files get [nowMs]; a mounted volume
         * without files is forgotten (its files are really gone).
         */
        internal fun evaluate(
            remembered: Map<String, Long>,
            withFiles: Set<String>,
            mounted: Set<String>,
            nowMs: Long,
        ): Pair<Boolean, Map<String, Long>> {
            val missing = remembered.filter { (name, at) -> name !in mounted && nowMs - at < FORGET_AFTER_MS }
            return missing.isEmpty() to (missing + withFiles.associateWith { nowMs })
        }
    }
}
