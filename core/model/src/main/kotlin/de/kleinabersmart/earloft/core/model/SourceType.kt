package de.kleinabersmart.earloft.core.model

/** Origin of a physical song source. */
enum class SourceType(
    /** A configured music server (favorites, scrobbles, streams, cast) – not a device. */
    val isServer: Boolean,
) {
    /** Music on the device (MediaStore). */
    LOCAL(isServer = false),

    /** Navidrome or another (Open)Subsonic server. */
    SUBSONIC(isServer = true),

    /** Jellyfin server (concept 14). */
    JELLYFIN(isServer = true),

    /** Emby server: the same implementation as Jellyfin with its own paths (concept 14). */
    EMBY(isServer = true),

    /**
     * Only on the watch: the song lives solely on the paired phone and comes via the Data Layer
     * (Wear concept 7.1). There is no data source for it, only the finished download is playable.
     */
    PHONE(isServer = false),
}
