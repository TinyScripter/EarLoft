package de.kleinabersmart.earloft.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a device may do during playback. The phone streams and plays over any output; the watch
 * (Wear concept 7.3, 9) only finished downloads and not over its own speaker. The watch
 * sets the values when the application starts, before the playback service runs.
 */
@Singleton
class PlaybackPolicy @Inject constructor() {

    /** `false`: [PlaybackSourceResolver] only returns local files and finished downloads. */
    @Volatile
    var allowStreaming: Boolean = true

    /** `true`: without a suitable output (e.g. headphones), playback is paused instead of playing over the speaker. */
    @Volatile
    var requireSuitableOutput: Boolean = false

    /**
     * `false`: progress and listening sessions of books are not sent to Audiobookshelf directly. The watch reports them to the phone,
     * which forwards them (Wear concept 16.2); the phone brings along the server's state.
     */
    @Volatile
    var syncsWithBookServers: Boolean = true

    /** `false`: shaking doesn't extend the sleep timer (watch: the wrist moves too easily during sleep). */
    @Volatile
    var shakeToExtendSleepTimer: Boolean = true
}
