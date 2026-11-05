package de.kleinabersmart.kleinaberlaut.core.data.repository

/** Baut aus einer Nutzereingabe einen sicheren FTS4-MATCH-Ausdruck mit Präfixsuche. */
internal object FtsQuery {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private const val MAX_TERMS = 8

    /** „Sigur Rós“ → `sigur* rós*`; `null`, wenn nichts Suchbares übrig bleibt. */
    fun build(input: String): String? =
        input.split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .take(MAX_TERMS)
            .joinToString(" ") { "${it.lowercase()}*" }
            .ifEmpty { null }
}
