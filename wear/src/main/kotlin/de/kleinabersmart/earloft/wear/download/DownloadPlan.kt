package de.kleinabersmart.earloft.wear.download

import de.kleinabersmart.earloft.core.database.model.WearCandidateRow
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.core.wearsync.WearSettingsDto

/** A song to be downloaded: the chosen server source and its estimated size. */
data class PlannedDownload(val songId: Long, val sourceUid: String, val estimatedBytes: Long, val pinnedBy: String)

/** [toQueue] fits into storage, [missing] tracks do not (Wear concept 8.1). */
data class DownloadPlan(val toQueue: List<PlannedDownload>, val missing: Int)

/**
 * Queues downloads in the given order as long as they fit under [budgetBytes]. The first track that no
 * longer fits ends it: this keeps the order of the selections, and nothing is deleted or moved ahead
 * without asking.
 */
fun planWithinBudget(ordered: List<PlannedDownload>, budgetBytes: Long): DownloadPlan {
    val fitting = takeWithinBudget(ordered, budgetBytes) { it.estimatedBytes }
    return DownloadPlan(fitting, missing = ordered.size - fitting.size)
}

/** The leading entries of [ordered] as long as their sizes ([bytes]) together fit under [budgetBytes]. */
fun <T> takeWithinBudget(ordered: List<T>, budgetBytes: Long, bytes: (T) -> Long): List<T> {
    var remaining = budgetBytes
    return ordered.takeWhile { item ->
        val size = bytes(item)
        (size <= remaining).also { if (it) remaining -= size }
    }
}

/**
 * Estimated size of a podcast episode (Wear concept 16.1): duration × 128 kbit/s, as many feeds offer. Without a duration
 * (the feed gave none) one hour.
 */
fun estimateEpisodeBytes(durationMs: Long): Long = (durationMs.takeIf { it > 0 } ?: UNKNOWN_EPISODE_MS) * EPISODE_BYTES_PER_MS

private const val UNKNOWN_EPISODE_MS = 3_600_000L
private const val EPISODE_BYTES_PER_MS = 16L // 128 kbit/s

/**
 * How much may still be queued: enough that [WearSettingsDto.minFreeBytes] stays free and the
 * fixed limit [WearSettingsDto.storageLimitBytes] (if set) is not exceeded. [reservedBytes]
 * are downloads already queued but not yet written.
 */
fun downloadBudget(settings: WearSettingsDto, freeBytes: Long, doneBytes: Long, reservedBytes: Long): Long {
    val byFreeSpace = freeBytes - settings.minFreeBytes
    val byLimit = settings.storageLimitBytes?.let { it - doneBytes } ?: Long.MAX_VALUE
    return minOf(byFreeSpace, byLimit) - reservedBytes
}

/**
 * Whether at least one waiting download still fits under [budgetBytes] when the job starts. Storage may have shrunk
 * between planning and start (other apps, covers, system data); then a start with a foreground notification and
 * Wi-Fi request is not worth it. Without waiting downloads there is nothing to check.
 */
fun hasRoomForAny(pendingEstimates: List<Long>, budgetBytes: Long): Boolean =
    pendingEstimates.isEmpty() || pendingEstimates.min() <= budgetBytes

/**
 * Estimated size of the file. Opus: duration × 128 kbit/s + 3 % container; original: the size of the source,
 * otherwise from the bitrate (default 320 kbit/s).
 */
fun estimateBytes(candidate: WearCandidateRow, quality: WearQuality): Long = when {
    // The phone always sends the original (Wear concept 8.4).
    candidate.sourceType == SourceType.PHONE || quality == WearQuality.ORIGINAL ->
        candidate.sizeBytes ?: (candidate.durationMs * (candidate.bitRate ?: DEFAULT_KBIT) / BITS_PER_BYTE)
    else -> (candidate.durationMs * OPUS_BYTES_PER_MS * OPUS_OVERHEAD).toLong()
}

/**
 * Rough duration in minutes of transferring [bytes] via the phone (rounded up, at least 1). The channel runs over
 * Bluetooth; a conservative throughput is enough for "approx. n min" (real values can only be checked on the Pixel Watch).
 */
fun estimatePhoneTransferMinutes(bytes: Long): Int =
    ((bytes + PHONE_BYTES_PER_MINUTE - 1) / PHONE_BYTES_PER_MINUTE).coerceAtLeast(1).toInt()

private const val PHONE_BYTES_PER_MINUTE = 100L * 1024 * 60 // approx. 100 KB/s (Pixel Watch 3: 92–102 KB/s, 2026-11-16)
private const val OPUS_BYTES_PER_MS = 128.0 / 8 // 128 kbit/s = 16 bytes per millisecond
private const val OPUS_OVERHEAD = 1.03
private const val DEFAULT_KBIT = 320
private const val BITS_PER_BYTE = 8
