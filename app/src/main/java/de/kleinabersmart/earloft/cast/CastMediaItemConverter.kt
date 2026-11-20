package de.kleinabersmart.earloft.cast

import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.albumIdExtra
import de.kleinabersmart.earloft.core.playback.songId
import de.kleinabersmart.earloft.core.playback.spokenCastSource
import de.kleinabersmart.earloft.core.playback.toMediaItem
import org.json.JSONObject

/**
 * `earloft://song/<id>` or a book/episode file → server stream for the cast receiver. The `contentId` is the media ID
 * (`song:<id>`); Media3 uses it to map the receiver's entries back to the original items,
 * so the queue on the phone keeps its `earloft://` URIs.
 */
@OptIn(UnstableApi::class)
internal class CastMediaItemConverter(private val cache: CastMediaCache) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val media = cache.get(mediaItem)
        val spoken = mediaItem.spokenCastSource != null
        if (media == null) Log.w(TAG, "${mediaItem.mediaId} has no server source")
        val meta = mediaItem.mediaMetadata
        val metadata = MediaMetadata(if (spoken) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            meta.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            if (spoken) {
                // Author or podcast name as subtitle; files of a book have no track and disc numbers.
                meta.artist?.let { putString(MediaMetadata.KEY_SUBTITLE, it.toString()) }
            } else {
                meta.artist?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
                meta.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
                meta.trackNumber?.let { putInt(MediaMetadata.KEY_TRACK_NUMBER, it) }
                meta.discNumber?.let { putInt(MediaMetadata.KEY_DISC_NUMBER, it) }
            }
            media?.coverUrl?.let { addImage(WebImage(it.toUri())) }
        }
        val info = MediaInfo.Builder(mediaItem.mediaId)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentUrl(media?.streamUrl.orEmpty())
            .setContentType(media?.mimeType)
            .setMetadata(metadata)
            .apply { meta.durationMs?.let { setStreamDuration(it) } }
            .apply { customData(meta)?.let(::setCustomData) }
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    /**
     * What the cast metadata doesn't carry but the app needs when rejoining: episode or book (outro, sleep timer,
     * resume) and for songs the album (cover, album colors, jump to album). Keep it short: the receiver's status
     * carries this for every queue entry (limit 64 KB, see `QueueReserve`).
     */
    private fun customData(meta: androidx.media3.common.MediaMetadata): JSONObject? {
        val json = JSONObject()
        meta.mediaType?.let { json.put(KEY_MEDIA_TYPE, it) }
        meta.albumIdExtra?.let { json.put(KEY_ALBUM_ID, it) }
        return json.takeIf { it.length() > 0 }
    }

    /**
     * For entries Media3 can't map to an app item: a foreign session on the receiver, or the app rejoins
     * its own after a restart. Then the kind (episode/book) comes from [KEY_MEDIA_TYPE]; a song with
     * [KEY_ALBUM_ID] is built as in the app (cover, album colors, duration), otherwise player and widget would lack the cover.
     */
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val metadata = info?.metadata
        val mediaType = info?.customData?.optInt(KEY_MEDIA_TYPE, -1)?.takeIf { it >= 0 }
        val albumId = info?.customData?.optLong(KEY_ALBUM_ID, -1L)?.takeIf { it >= 0 }
        val songId = MediaItem.Builder().setMediaId(info?.contentId.orEmpty()).build().songId
        if (info != null && songId != null && albumId != null) {
            return Song(
                id = songId,
                title = metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.KEY_ARTIST).orEmpty(),
                albumId = albumId,
                albumTitle = metadata?.getString(MediaMetadata.KEY_ALBUM_TITLE).orEmpty(),
                durationMs = info.streamDuration.takeIf { it > 0 } ?: 0,
                discNumber = metadata?.getInt(MediaMetadata.KEY_DISC_NUMBER) ?: 0,
                trackNumber = metadata?.getInt(MediaMetadata.KEY_TRACK_NUMBER) ?: 0,
                isFavorite = false,
            ).toMediaItem()
        }
        val item = MediaItem.Builder()
            .setMediaId(info?.contentId.orEmpty())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadata?.getString(MediaMetadata.KEY_TITLE))
                    // Books and episodes carry the author or podcast as subtitle.
                    .setArtist(metadata?.getString(MediaMetadata.KEY_ARTIST) ?: metadata?.getString(MediaMetadata.KEY_SUBTITLE))
                    .setAlbumTitle(metadata?.getString(MediaMetadata.KEY_ALBUM_TITLE))
                    .setMediaType(mediaType)
                    .build(),
            )
            .build()
        return item.songId?.let { item.buildUpon().setUri(AppUris.song(it)).build() } ?: item
    }

    private companion object {
        const val TAG = "CastMediaItemConverter"

        /** `customData` of the entry: Media3's `MediaMetadata.mediaType`. */
        const val KEY_MEDIA_TYPE = "earloftMediaType"

        /** `customData` of a song: its album ID. */
        const val KEY_ALBUM_ID = "earloftAlbum"
    }
}
