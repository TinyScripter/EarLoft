package de.kleinabersmart.tinyamp.ui

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
