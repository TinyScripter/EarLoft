package keinabersmart.kleinaberlaut.ui

import java.util.Locale

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * Dateigröße im SI-Maßstab (1 KB = 1000 B) mit deutschem Dezimalkomma. Bewusst nicht
 * `Formatter.formatShortFileSize`: Das folgt der System-Sprache, die App-Texte sind aber nur deutsch.
 */
fun formatBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val decimals = if (unit == 0 || value >= 100) 0 else 1
    return String.format(Locale.GERMAN, "%.${decimals}f %s", value, UNITS[unit])
}

/**
 * Restzeit des Sleep-Timers: `m:ss` unter einer Stunde, ab einer Stunde `h:mm` ohne Sekunden. Die Minuten werden
 * aufgerundet, damit die Anzeige nie weniger Zeit verspricht, als noch bleibt.
 */
fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
    if (totalSeconds < 3600) return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    val totalMinutes = (totalSeconds + 59) / 60
    return "%d:%02d".format(totalMinutes / 60, totalMinutes % 60)
}
