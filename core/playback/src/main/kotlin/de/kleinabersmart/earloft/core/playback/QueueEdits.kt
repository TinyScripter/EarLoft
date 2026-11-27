package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.Player

/**
 * Removes everything after the current title (a mix replaces what follows). Always, even if nothing follows in the player: while
 * casting the factory's reserve can still hold titles behind the receiver's window, and only a clearing up to the end drops them
 * (`DeferredRemotePlayPlayer` reports it to the factory). Otherwise new titles would be appended behind the reserve, and the old queue
 * would play before them. An empty range is allowed by [Player.removeMediaItems]; a `MediaController` passes it on to the session.
 */
internal fun Player.clearAfterCurrent() {
    if (mediaItemCount == 0) return
    removeMediaItems(currentMediaItemIndex + 1, mediaItemCount)
}
