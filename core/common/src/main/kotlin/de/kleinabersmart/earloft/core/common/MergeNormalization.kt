package de.kleinabersmart.earloft.core.common

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

/**
 * Comparison key for deduplicating artists, albums and songs.
 *
 * Removes case, diacritics, punctuation and whitespace, but keeps
 * letters and digits of all scripts ("Sigur Rós" → "sigurros", "東京事変" is preserved).
 */
fun String.normalizeForMerge(): String =
    Normalizer.normalize(trim().lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .replace(NON_ALNUM, "")

/** Sort key without a leading English article ("The Beatles" → "Beatles"). */
fun String.sortKey(): String = trim().removePrefix("The ").removePrefix("the ")

/**
 * Stored sort key of artists and albums and the order of other lists sorted by name (review G14): [sortKey] without case,
 * accents and punctuation, the way the book lists sort. "Die Ärzte" sorts under D, "Ärzte" and "Édith Piaf" with A and E
 * (SQLite's `NOCASE` only folds ASCII and put them behind Z).
 */
fun String.foldedSortKey(): String = sortKey().normalizeForMerge().ifEmpty { trim().lowercase(Locale.ROOT) }
