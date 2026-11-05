package de.kleinabersmart.tinyamp.core.common

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

/**
 * Vergleichsschlüssel für die Deduplizierung von Künstlern, Alben und Songs.
 *
 * Entfernt Groß-/Kleinschreibung, Diakritika, Satz- und Leerzeichen, behält aber
 * Buchstaben und Ziffern aller Schriften ("Sigur Rós" → "sigurros", "東京事変" bleibt erhalten).
 */
fun String.normalizeForMerge(): String =
    Normalizer.normalize(trim().lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .replace(NON_ALNUM, "")

/** Sortierschlüssel ohne führenden englischen Artikel („The Beatles“ → „Beatles“). */
fun String.sortKey(): String = trim().removePrefix("The ").removePrefix("the ")
