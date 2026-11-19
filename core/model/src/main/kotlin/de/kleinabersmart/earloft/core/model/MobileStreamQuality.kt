package de.kleinabersmart.earloft.core.model

/**
 * Quality of streams over metered networks (mobile data), concept 7.4 "bitrate over mobile data". [transcodeTo] is the
 * format and the maximum bitrate in kbit/s the server transcodes to; `null` means original. On Wi-Fi it's always original.
 */
enum class MobileStreamQuality(val transcodeTo: Pair<String, Int>?) {
    ORIGINAL(null),
    OPUS_192("opus" to 192),
    OPUS_128("opus" to 128),
}
