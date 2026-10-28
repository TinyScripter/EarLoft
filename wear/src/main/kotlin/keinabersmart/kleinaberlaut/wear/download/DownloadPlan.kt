package keinabersmart.kleinaberlaut.wear.download

import keinabersmart.kleinaberlaut.core.database.model.WearCandidateRow
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto

/** Ein Song, der geladen werden soll: die gewählte Server-Quelle und ihre geschätzte Größe. */
data class PlannedDownload(val songId: Long, val sourceUid: String, val estimatedBytes: Long, val pinnedBy: String)

/** [toQueue] passt in den Speicher, [missing] Titel nicht (Wear-Konzept 8.1). */
data class DownloadPlan(val toQueue: List<PlannedDownload>, val missing: Int)

/**
 * Reiht Downloads in der gegebenen Reihenfolge ein, solange sie unter [budgetBytes] passen. Beim ersten
 * Titel, der nicht mehr passt, ist Schluss: So bleibt die Reihenfolge der Auswahlen erhalten, und es wird
 * nichts ungefragt gelöscht oder vorgezogen.
 */
fun planWithinBudget(ordered: List<PlannedDownload>, budgetBytes: Long): DownloadPlan {
    var remaining = budgetBytes
    val fitting = ordered.takeWhile { download ->
        (download.estimatedBytes <= remaining).also { if (it) remaining -= download.estimatedBytes }
    }
    return DownloadPlan(fitting, missing = ordered.size - fitting.size)
}

/**
 * Wie viel noch eingereiht werden darf: So viel, dass [WearSettingsDto.minFreeBytes] frei bleibt und die
 * feste Grenze [WearSettingsDto.storageLimitBytes] (falls gesetzt) nicht überschritten wird. [reservedBytes]
 * sind schon eingereihte, aber noch nicht geschriebene Downloads.
 */
fun downloadBudget(settings: WearSettingsDto, freeBytes: Long, doneBytes: Long, reservedBytes: Long): Long {
    val byFreeSpace = freeBytes - settings.minFreeBytes
    val byLimit = settings.storageLimitBytes?.let { it - doneBytes } ?: Long.MAX_VALUE
    return minOf(byFreeSpace, byLimit) - reservedBytes
}

/**
 * Geschätzte Größe der Datei. Opus: Dauer × 128 kbit/s + 3 % Container; Original: die Größe der Quelle,
 * sonst aus der Bitrate (Standard 320 kbit/s).
 */
fun estimateBytes(candidate: WearCandidateRow, quality: WearQuality): Long = when {
    // Das Handy schickt immer das Original (Wear-Konzept 8.4).
    candidate.sourceType == SourceType.PHONE || quality == WearQuality.ORIGINAL ->
        candidate.sizeBytes ?: (candidate.durationMs * (candidate.bitRate ?: DEFAULT_KBIT) / BITS_PER_BYTE)
    else -> (candidate.durationMs * OPUS_BYTES_PER_MS * OPUS_OVERHEAD).toLong()
}

private const val OPUS_BYTES_PER_MS = 128.0 / 8 // 128 kbit/s = 16 Byte je Millisekunde
private const val OPUS_OVERHEAD = 1.03
private const val DEFAULT_KBIT = 320
private const val BITS_PER_BYTE = 8
