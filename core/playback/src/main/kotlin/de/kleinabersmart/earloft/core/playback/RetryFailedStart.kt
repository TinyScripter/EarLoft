package de.kleinabersmart.earloft.core.playback

/**
 * Whether a start that failed is loaded again ([PlaybackConnection.retryFailedStart]): the player stopped with an error while it was
 * meant to play (`playWhenReady` stays set after a loading error) and still holds titles. A queue paused by the user, or one without
 * error, is left alone: granting a permission must not start playback nobody asked for.
 */
internal fun shouldRetryFailedStart(hasError: Boolean, playWhenReady: Boolean, itemCount: Int): Boolean =
    hasError && playWhenReady && itemCount > 0
