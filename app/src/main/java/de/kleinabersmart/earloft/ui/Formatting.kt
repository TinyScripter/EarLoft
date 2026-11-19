package de.kleinabersmart.earloft.ui

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val UNITS = listOf(MeasureUnit.BYTE, MeasureUnit.KILOBYTE, MeasureUnit.MEGABYTE, MeasureUnit.GIGABYTE, MeasureUnit.TERABYTE)

/**
 * File size on the SI scale (1 kB = 1000 B), written the way [locale] writes it: decimal separator, spacing and
 * unit abbreviation come from ICU ("1.5 GB" in English, "1,5 GB" in German, "1,5 Go" in French). One decimal below
 * 100, none from 100 on and for plain bytes.
 */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    // 999.96 kB would round to "1000 kB": switch to the next unit before rounding gets there.
    while (value >= 999.5 && unit < UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val decimals = if (unit == 0 || value >= 99.95) 0 else 1
    return measureFormat(locale, decimals).format(Measure(value, UNITS[unit]))
}

/** A share between 0 and 1 as a whole percentage the way [locale] writes it ("42%" in English, "42 %" in German and French). */
fun formatPercent(fraction: Double, locale: Locale = Locale.getDefault()): String {
    val format = percentFormats.getOrPut(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 } }
    // NumberFormat is not thread-safe; sizes and percentages are also formatted for notifications.
    return synchronized(format) { format.format(fraction.coerceIn(0.0, 1.0)) }
}

// Formatters are built once per locale (download rows format several times a second). MeasureFormat is immutable and
// thread-safe; it copies the NumberFormat it gets.
private val measureFormats = ConcurrentHashMap<Pair<Locale, Int>, MeasureFormat>()
private val percentFormats = ConcurrentHashMap<Locale, NumberFormat>()

private fun measureFormat(locale: Locale, decimals: Int): MeasureFormat = measureFormats.getOrPut(locale to decimals) {
    val number = NumberFormat.getInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        roundingMode = BigDecimal.ROUND_HALF_UP
    }
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, number)
}
