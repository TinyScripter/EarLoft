package de.kleinabersmart.tinyamp.core.data.share

/**
 * Podcast-Adressen, die andere Apps an TinyAmp geben (Konzept 13): geteilter Text oder ein Link mit den Schemata der
 * Podcast-Apps. Ergebnis ist eine Adresse für die Vorschau (`PodcastRepository.openPreview`), die selbst entscheidet, ob es
 * ein Feed ist.
 */
object IncomingFeed {
    private val SCHEMES = setOf("feed", "pcast", "itpc", "podcast")
    private val URL = Regex("""(?:https?|feed|pcast|itpc|podcast)://[^\s<>"']+|(?:feed|pcast):https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val RSS_LINE = Regex("""^\s*RSS:\s*(\S+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * `feed://example.com/rss`, `feed:https://example.com/rss`, `itpc://…` usw.; sonst `null`. Ohne Angabe wird es
     * `http:` – die Vorschau versucht zuerst still HTTPS (Konzept 10), so gehen auch Feeds, die es nur per HTTP gibt.
     */
    fun fromLink(link: String): String? {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme !in SCHEMES) return null
        val rest = trimmed.substringAfter(':')
        return when {
            rest.startsWith("http://", ignoreCase = true) || rest.startsWith("https://", ignoreCase = true) -> rest
            rest.startsWith("//") && rest.length > 2 -> "http:$rest"
            else -> null
        }
    }

    /** Aus geteiltem Text die Adresse nach „RSS:“ (so teilt TinyAmp selbst), sonst die erste Adresse. */
    fun fromText(text: String): String? {
        val candidate = RSS_LINE.find(text)?.groupValues?.get(1) ?: URL.find(text)?.value ?: return null
        val address = candidate.trimEnd('.', ',', ')', ']', ';')
        return fromLink(address) ?: address.takeIf { it.startsWith("http", ignoreCase = true) }
    }
}
