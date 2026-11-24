package de.kleinabersmart.earloft.core.datasource.local

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The files of a podcast from the "Podcasts" folder. */
internal data class LocalPodcastFiles(val name: String, val files: List<AudioFileRow>)

/**
 * Which files belong to which podcast (concept 12.1): a subfolder of "Podcasts" is a podcast; files directly
 * in "Podcasts" (as a browser or a podcast app saves them) are grouped by their album tag, otherwise by the
 * artist, otherwise as one shared podcast that the caller names.
 */
internal object LocalPodcastLayout {
    const val ROOT = "Podcasts"

    private const val UNKNOWN = "<unknown>"

    /** @param untitled Name of the catch-all podcast for files without a usable album or artist tag. */
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

    /** MediaStore sets the folder name as album if the file has no album tag ("Podcasts"); that doesn't count. */
    private fun tagName(row: AudioFileRow): String? {
        fun usable(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN && !it.equals(ROOT, ignoreCase = true) }
        return usable(row.album) ?: usable(row.albumArtist) ?: usable(row.artist)
    }

    /** Key of an episode: path from "Podcasts/". */
    fun key(row: AudioFileRow): String = LocalBookLayout.relativePath(row.path, ROOT) ?: row.path

    /** Changes as soon as the file changes. */
    fun fingerprint(row: AudioFileRow): Long =
        "${row.id}|${row.sizeBytes}|${row.dateModifiedSec}".hashCode().toLong() xor (row.sizeBytes shl 32)

    /**
     * Date from a tag: "2026", "2026-10-29", "20261029" or with time ("2026-10-29T08:00:00") becomes midnight
     * UTC (January 1 for a year only); anything else is `null`.
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

    /**
     * Publication time of a file from the device: the tag date ([parseTagDate]), otherwise when the file arrived
     * ([fileAddedMs]). A tag with only the year (or year and month) in which the file arrived counts as its arrival: "2026" as
     * January 1 dropped a file copied just now out of "Recently added" at once (review3 Q18).
     */
    fun publishedAt(rawTag: String?, fileAddedMs: Long): Long {
        val tagged = parseTagDate(rawTag) ?: return fileAddedMs
        val match = DATE.find(rawTag?.trim().orEmpty()) ?: return tagged
        val (year, month, day) = match.destructured
        if (day.isNotEmpty() || fileAddedMs <= 0) return tagged
        val added = Instant.ofEpochMilli(fileAddedMs).atZone(ZoneOffset.UTC).toLocalDate()
        val sameSpan = added.year == year.toInt() && (month.isEmpty() || added.monthValue == month.toInt())
        return if (sameSpan) fileAddedMs else tagged
    }

    private val DATE = Regex("""^(\d{4})(?:-?(\d{2})(?:-?(\d{2}))?)?(?!\d)""")
}
