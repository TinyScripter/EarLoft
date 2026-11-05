package de.kleinabersmart.kleinaberlaut.core.model

data class LyricLine(
    val startMs: Long,
    val text: String,
)

/** Songtext eines Songs. */
sealed interface Lyrics {
    /** Zeilen mit Zeitstempel, aufsteigend sortiert. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    data class Plain(val text: String) : Lyrics

    /** Die Quellen haben keinen Text. */
    data object None : Lyrics

    /** Keine Quelle erreichbar – nichts gespeichert, ein späterer Versuch kann klappen. */
    data object Unavailable : Lyrics
}

/**
 * Speicherform in `lyrics.content`: synchronisierte Lyrics als `startMs<TAB>Text` je Zeile,
 * statische Lyrics als Text.
 */
object LyricsCodec {
    fun encode(lyrics: Lyrics): Pair<LyricsKind, String> = when (lyrics) {
        is Lyrics.Synced -> LyricsKind.SYNCED to lyrics.lines.joinToString("\n") { "${it.startMs}\t${it.text.replace('\n', ' ')}" }
        is Lyrics.Plain -> LyricsKind.PLAIN to lyrics.text
        Lyrics.None, Lyrics.Unavailable -> LyricsKind.NONE to ""
    }

    fun decode(kind: LyricsKind, content: String): Lyrics = when (kind) {
        LyricsKind.SYNCED -> {
            val lines = content.lines().mapNotNull { line ->
                val tab = line.indexOf('\t')
                val start = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
                start?.let { LyricLine(it, line.substring(tab + 1)) }
            }
            if (lines.isEmpty()) Lyrics.None else Lyrics.Synced(lines)
        }
        LyricsKind.PLAIN -> if (content.isBlank()) Lyrics.None else Lyrics.Plain(content)
        LyricsKind.NONE -> Lyrics.None
    }
}
