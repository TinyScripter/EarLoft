package de.kleinabersmart.earloft.core.data.repository

import android.net.Uri

/**
 * Serves the phone's files to a cast receiver (concept 7.5, Chromecast stage 2): a small HTTP server on the
 * Wi-Fi that only runs during a cast session. The URLs contain a session secret – don't store, don't
 * log. Bound in the app.
 */
interface PhoneCastServer {

    /** URL for [uri] (`content://` or `file://`); `null` if the phone currently has no address on the Wi-Fi. */
    fun fileUrl(uri: Uri, mimeType: String): String?

    /** URL for the cover of an album, loaded only when requested; `null` as with [fileUrl]. */
    fun albumCoverUrl(albumId: Long): String?

    /** URL for the cover of a book or episode; `null` as with [fileUrl]. */
    fun bookCoverUrl(bookId: Long): String?

    companion object {
        /** [CastMedia.sourceId] for files from the phone; can be excluded like a server the receiver can't reach. */
        const val SOURCE_ID = "phone"
    }
}
