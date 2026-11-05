package de.kleinabersmart.tinyamp.core.datasource.local

import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset

/** Die Dateien eines Podcasts aus dem Ordner „Podcasts“. */
internal data class LocalPodcastFiles(val name: String, val files: List<AudioFileRow>)

/**
 * Welche Dateien zu welchem Podcast gehören (Konzept 12.1): Ein Unterordner von „Podcasts“ ist ein Podcast; Dateien direkt
 * in „Podcasts“ (wie ein Browser oder eine Podcast-App sie ablegt) gelten nach ihrem Album-Tag, sonst nach dem
 * Interpreten, sonst als ein gemeinsamer Podcast, den der Aufrufer benennt.
 */
internal object LocalPodcastLayout {
    const val ROOT = "Podcasts"

    private const val UNKNOWN = "<unknown>"

    /** @param untitled Name des Sammel-Podcasts für Dateien ohne brauchbaren Album- oder Interpreten-Tag. */
    fun group(rows: List<AudioFileRow>, untitled: String): List<LocalPodcastFiles> {
        val byName = linkedMapOf<String, MutableList<AudioFileRow>>()
        for (row in rows) {
            val relative = LocalBookLayout.relativePath(row.path, ROOT) ?: continue
            val folders = relative.split('/').drop(1).dropLast(1)
            val name = folders.firstOrNull() ?: tagName(row) ?: untitled
            byName.getOrPut(name) { mutableListOf() } += row
        }
        return byName.map { (name, files) -> LocalPodcastFiles(name, files) }.sortedBy { it.name.lowercase() }
    }

    /** MediaStore setzt den Ordnernamen als Album, wenn die Datei keinen Album-Tag hat („Podcasts“); der zählt nicht. */
    private fun tagName(row: AudioFileRow): String? {
        fun usable(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN && !it.equals(ROOT, ignoreCase = true) }
        return usable(row.album) ?: usable(row.albumArtist) ?: usable(row.artist)
    }

    /** Schlüssel einer Folge: Pfad ab „Podcasts/“. */
    fun key(row: AudioFileRow): String = LocalBookLayout.relativePath(row.path, ROOT) ?: row.path

    /** Ändert sich, sobald sich die Datei ändert. */
    fun fingerprint(row: AudioFileRow): Long =
        "${row.id}|${row.sizeBytes}|${row.dateModifiedSec}".hashCode().toLong() xor (row.sizeBytes shl 32)

    /**
     * Datum aus einem Tag: „2026“, „2026-10-29“, „20261029“ oder mit Uhrzeit („2026-10-29T08:00:00“) wird zu Mitternacht
     * UTC (bei nur einem Jahr der 1. Januar); alles andere ist `null`.
     */
    fun parseTagDate(raw: String?): Long? {
        val match = DATE.find(raw?.trim().orEmpty()) ?: return null
        val (year, month, day) = match.destructured
        return try {
            LocalDate.of(year.toInt(), month.ifEmpty { "1" }.toInt(), day.ifEmpty { "1" }.toInt())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    private val DATE = Regex("""^(\d{4})(?:-?(\d{2})(?:-?(\d{2}))?)?(?!\d)""")
}
