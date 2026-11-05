package de.kleinabersmart.kleinaberlaut.core.model

/**
 * Wandelt eingebettete Songtexte in [Lyrics]. Enthält der Text `[mm:ss.xx]`-Zeitstempel (LRC), wird er
 * synchronisiert; mehrere Stempel je Zeile ergeben mehrere Zeilen. Metazeilen wie `[ar:…]` entfallen.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META = Regex("""^\[[A-Za-z]+:.*]$""")

    fun parse(raw: String): Lyrics {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return Lyrics.None

        val lines = mutableListOf<LyricLine>()
        for (line in text.lines()) {
            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty() || stamps.first().range.first != 0) continue
            val content = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val (minutes, seconds, fraction) = stamp.destructured
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.toLong()
                }
                lines += LyricLine(minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs, content)
            }
        }
        if (lines.isNotEmpty()) return Lyrics.Synced(lines.sortedBy { it.startMs })

        val plain = text.lines().filterNot { META.matches(it.trim()) }.joinToString("\n").trim()
        return if (plain.isEmpty()) Lyrics.None else Lyrics.Plain(plain)
    }
}
