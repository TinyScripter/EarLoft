package de.kleinabersmart.earloft.core.model

/**
 * Converts embedded lyrics into [Lyrics]. If the text contains `[mm:ss.xx]` timestamps (LRC), it becomes
 * synced; multiple stamps per line result in multiple lines. Meta lines like `[ar:…]` are dropped; `[offset:±ms]`
 * shifts all lines (positive = earlier, as in the OpenSubsonic specification).
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META = Regex("""^\[[A-Za-z]+:.*]$""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Lyrics {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return Lyrics.None

        val offsetMs = text.lines().firstNotNullOfOrNull { OFFSET.find(it.trim())?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
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
                val startMs = minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
                lines += LyricLine((startMs - offsetMs).coerceAtLeast(0), content)
            }
        }
        if (lines.isNotEmpty()) return Lyrics.Synced(lines.sortedBy { it.startMs })

        val plain = text.lines().filterNot { META.matches(it.trim()) }.joinToString("\n").trim()
        return if (plain.isEmpty()) Lyrics.None else Lyrics.Plain(plain)
    }
}
