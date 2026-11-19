package de.kleinabersmart.earloft.core.data.share

/**
 * Podcast addresses that other apps hand to EarLoft (concept 13): shared text or a link with the schemes of
 * podcast apps. The result is an address for the preview (`PodcastRepository.openPreview`), which decides itself whether it
 * is a feed.
 */
object IncomingFeed {
    private val SCHEMES = setOf("feed", "pcast", "itpc", "podcast")
    private val URL = Regex("""(?:https?|feed|pcast|itpc|podcast)://[^\s<>"']+|(?:feed|pcast):https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val RSS_LINE = Regex("""^\s*RSS:\s*(\S+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * `feed://example.com/rss`, `feed:https://example.com/rss`, `itpc://…` etc.; otherwise `null`. Without a scheme it becomes
     * `http:` – the preview first silently tries HTTPS (concept 10), so feeds only available over HTTP work too.
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

    /** From shared text, the address after "RSS:" (that's how EarLoft itself shares), otherwise the first address. */
    fun fromText(text: String): String? {
        val candidate = RSS_LINE.find(text)?.groupValues?.get(1) ?: URL.find(text)?.value ?: return null
        val address = candidate.trimEnd('.', ',', ')', ']', ';')
        return fromLink(address) ?: address.takeIf { it.startsWith("http", ignoreCase = true) }
    }
}
