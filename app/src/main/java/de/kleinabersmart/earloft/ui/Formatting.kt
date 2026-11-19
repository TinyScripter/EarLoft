package de.kleinabersmart.earloft.ui

import java.util.Locale

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * File size on the SI scale (1 KB = 1000 B) with a German decimal comma. Deliberately not
 * `Formatter.formatShortFileSize`: that follows the system language, but the app texts are German only.
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
