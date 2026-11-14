package de.kleinabersmart.earloft.core.network.podcast

/** `&name;`, `&#123;` oder `&#x7B;`. */
private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")

/** Die benannten Entities, die in Titeln von Feeds vorkommen; unbekannte bleiben stehen. */
private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•", "middot" to "·",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
    "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
    "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ccedil" to "ç",
    "euro" to "€", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "times" to "×",
)

/**
 * Löst HTML-Entities in Klartext genau einmal auf (aus `&amp;amp;` wird `&amp;`, nicht `&`). Für Titel und Namen aus
 * Feeds, die nach dem XML-Parser noch kodiert sind; HTML-Beschreibungen setzt die Oberfläche selbst um.
 */
fun decodeHtmlEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        val codePoint = when {
            body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
            body.startsWith("#") -> body.substring(1).toIntOrNull()
            else -> return@replace NAMED[body] ?: match.value
        }
        if (codePoint == null || codePoint == 0 || codePoint > Character.MAX_CODE_POINT || codePoint in 0xD800..0xDFFF) {
            match.value
        } else {
            String(Character.toChars(codePoint))
        }
    }
}
