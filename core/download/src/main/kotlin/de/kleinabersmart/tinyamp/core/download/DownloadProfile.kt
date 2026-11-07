package de.kleinabersmart.tinyamp.core.download

/** In welcher Form der Server die Datei liefert (Wear-Konzept 8.2). */
enum class DownloadProfile(
    /** Ziel der Umwandlung auf dem Server (Codec, höchstens kbit/s); `null` = Original. */
    val transcodeTo: Pair<String, Int>?,
) {
    /** Die Datei wie auf dem Server. */
    ORIGINAL(transcodeTo = null),

    /**
     * Der Server transkodiert beim Download nach Opus mit höchstens 128 kbit/s (Navidrome braucht dafür
     * ffmpeg). Quellen ohne [de.kleinabersmart.tinyamp.core.datasource.RemoteTranscoding] liefern das Original.
     */
    OPUS_128(transcodeTo = "opus" to 128),
}
