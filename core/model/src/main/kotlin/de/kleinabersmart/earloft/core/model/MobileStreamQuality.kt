package de.kleinabersmart.earloft.core.model

/**
 * Qualität von Streams über getaktete Netze (mobile Daten), Konzept 7.4 „Bitrate über mobile Daten“. [transcodeTo] ist das
 * Format und die höchste Bitrate in kbit/s, in die der Server umwandelt; `null` heißt Original. Im WLAN gilt immer Original.
 */
enum class MobileStreamQuality(val transcodeTo: Pair<String, Int>?) {
    ORIGINAL(null),
    OPUS_192("opus" to 192),
    OPUS_128("opus" to 128),
}
