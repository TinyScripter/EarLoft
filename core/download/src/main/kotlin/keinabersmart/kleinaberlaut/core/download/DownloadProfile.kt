package keinabersmart.kleinaberlaut.core.download

import okhttp3.HttpUrl

/** In welcher Form der Server die Datei liefert (Wear-Konzept 8.2). */
enum class DownloadProfile {
    /** Die Datei wie auf dem Server (`format=raw` der Stream-URL). */
    ORIGINAL,

    /** Navidrome transkodiert beim Download nach Opus mit höchstens 128 kbit/s (braucht ffmpeg auf dem Server). */
    OPUS_128,
    ;

    internal fun apply(url: HttpUrl): HttpUrl = when (this) {
        ORIGINAL -> url
        OPUS_128 -> url.newBuilder()
            .setQueryParameter("format", "opus")
            .setQueryParameter("maxBitRate", "128")
            .build()
    }
}
