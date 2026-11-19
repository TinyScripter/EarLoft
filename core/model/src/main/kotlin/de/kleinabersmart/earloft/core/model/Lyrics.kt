package de.kleinabersmart.earloft.core.model

data class LyricLine(
    val startMs: Long,
    val text: String,
)

/** Lyrics of a song. */
sealed interface Lyrics {
    /** Lines with timestamps, sorted ascending. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    data class Plain(val text: String) : Lyrics

    /** The sources have no lyrics. */
    data object None : Lyrics

    /** No source reachable – nothing stored, a later attempt may succeed. */
    data object Unavailable : Lyrics
}

/**
 * Storage format in `lyrics.content`: synced lyrics as `startMs<TAB>text` per line,
 * static lyrics as text.
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
