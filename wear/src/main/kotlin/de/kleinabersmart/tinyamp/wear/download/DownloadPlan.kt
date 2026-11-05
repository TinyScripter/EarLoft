package de.kleinabersmart.tinyamp.wear.download

import de.kleinabersmart.tinyamp.core.database.model.WearCandidateRow
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.wearsync.WearQuality
import de.kleinabersmart.tinyamp.core.wearsync.WearSettingsDto

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
    val fitting = takeWithinBudget(ordered, budgetBytes) { it.estimatedBytes }
    return DownloadPlan(fitting, missing = ordered.size - fitting.size)
}

/** Die vorderen Einträge von [ordered], solange ihre Größen ([bytes]) zusammen unter [budgetBytes] passen. */
fun <T> takeWithinBudget(ordered: List<T>, budgetBytes: Long, bytes: (T) -> Long): List<T> {
    var remaining = budgetBytes
    return ordered.takeWhile { item ->
        val size = bytes(item)
        (size <= remaining).also { if (it) remaining -= size }
    }
}

/**
 * Geschätzte Größe einer Podcast-Folge (Wear-Konzept 16.1): Dauer × 128 kbit/s, wie sie viele Feeds anbieten. Ohne Dauer
 * (der Feed nannte keine) eine Stunde.
 */
fun estimateEpisodeBytes(durationMs: Long): Long = (durationMs.takeIf { it > 0 } ?: UNKNOWN_EPISODE_MS) * EPISODE_BYTES_PER_MS

private const val UNKNOWN_EPISODE_MS = 3_600_000L
private const val EPISODE_BYTES_PER_MS = 16L // 128 kbit/s

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
 * Ob beim Start des Auftrags wenigstens ein wartender Download noch unter [budgetBytes] passt. Zwischen Planung und
 * Start kann der Speicher geschrumpft sein (andere Apps, Cover, Systemdaten); dann lohnt sich kein Start mit
 * Vordergrund-Benachrichtigung und WLAN-Anforderung. Ohne wartende Downloads gibt es nichts zu prüfen.
 */
fun hasRoomForAny(pendingEstimates: List<Long>, budgetBytes: Long): Boolean =
    pendingEstimates.isEmpty() || pendingEstimates.min() <= budgetBytes

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

/**
 * Grobe Dauer der Übertragung von [bytes] über das Handy in Minuten (aufgerundet, mindestens 1). Der Kanal läuft über
 * Bluetooth; ein vorsichtiger Durchsatz genügt für „ca. n Min.“ (echte Werte nur auf der Pixel Watch prüfbar).
 */
fun estimatePhoneTransferMinutes(bytes: Long): Int =
    ((bytes + PHONE_BYTES_PER_MINUTE - 1) / PHONE_BYTES_PER_MINUTE).coerceAtLeast(1).toInt()

private const val PHONE_BYTES_PER_MINUTE = 100L * 1024 * 60 // ca. 100 KB/s
private const val OPUS_BYTES_PER_MS = 128.0 / 8 // 128 kbit/s = 16 Byte je Millisekunde
private const val OPUS_OVERHEAD = 1.03
private const val DEFAULT_KBIT = 320
private const val BITS_PER_BYTE = 8
