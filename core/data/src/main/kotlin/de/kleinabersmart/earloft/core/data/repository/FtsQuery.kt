package de.kleinabersmart.earloft.core.data.repository

/** Builds a safe FTS4 MATCH expression with prefix search from user input. */
internal object FtsQuery {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private const val MAX_TERMS = 8

    /** "Sigur Rós" → `sigur* rós*`; `null` if nothing searchable remains. */
    fun build(input: String): String? =
        input.split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .take(MAX_TERMS)
            .joinToString(" ") { "${it.lowercase()}*" }
            .ifEmpty { null }
}
