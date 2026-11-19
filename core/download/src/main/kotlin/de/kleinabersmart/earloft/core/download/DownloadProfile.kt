package de.kleinabersmart.earloft.core.download

/** In which form the server delivers the file (Wear concept 8.2). */
enum class DownloadProfile(
    /** Target of the conversion on the server (codec, max kbit/s); `null` = original. */
    val transcodeTo: Pair<String, Int>?,
) {
    /** The file as it is on the server. */
    ORIGINAL(transcodeTo = null),

    /**
     * The server transcodes to Opus at max 128 kbit/s while downloading (Navidrome needs
     * ffmpeg for this). Sources without [de.kleinabersmart.earloft.core.datasource.RemoteTranscoding] deliver the original.
     */
    OPUS_128(transcodeTo = "opus" to 128),
}
