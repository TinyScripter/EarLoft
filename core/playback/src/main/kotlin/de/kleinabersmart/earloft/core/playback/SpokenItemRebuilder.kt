package de.kleinabersmart.earloft.core.playback

import androidx.media3.common.MediaItem
import de.kleinabersmart.earloft.core.data.spoken.BookPlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * If the app attaches to a running cast session after a restart, Media3 only knows the receiver's entries:
 * books and episodes come back to the phone without an address, and when switching to the phone they'd be dropped ("Nothing
 * playing"). Here they are rebuilt from the database, with the same `mediaId` and file, including downloaded file,
 * cover and cast addresses.
 */
@Singleton
class SpokenItemRebuilder @Inject constructor(private val source: BookPlaybackSource) {

    /**
     * [items] with rebuilt books and episodes where the address is missing; everything else stays. Synchronous for Media3's
     * switch, only reads the database. Whatever can't be built (book deleted) stays as it was.
     */
    fun playable(items: List<MediaItem>): List<MediaItem> {
        val missing = items.filter { it.localConfiguration == null }.mapNotNullTo(LinkedHashSet()) { it.bookId }
        if (missing.isEmpty()) return items
        val rebuilt = runBlocking(Dispatchers.IO) {
            missing.associateWith { bookId -> source.rebuild(bookId)?.toMediaItems().orEmpty() }
        }
        return items.map { item ->
            if (item.localConfiguration != null) return@map item
            val bookId = item.bookId ?: return@map item
            item.bookTrackIndex?.let { rebuilt[bookId]?.getOrNull(it) } ?: item
        }
    }
}
