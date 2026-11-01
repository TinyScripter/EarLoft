package keinabersmart.kleinaberlaut.cast

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import keinabersmart.kleinaberlaut.core.model.AppUris
import keinabersmart.kleinaberlaut.core.playback.songId
import keinabersmart.kleinaberlaut.core.playback.spokenCastSource

/**
 * `kal://song/<id>` bzw. Buch-/Folgen-Datei → Server-Stream für den Cast-Empfänger. Die `contentId` ist die Media-ID
 * (`song:<id>`); darüber ordnet Media3 die Einträge des Empfängers wieder den ursprünglichen Items zu,
 * die Warteschlange auf dem Handy behält also ihre `kal://`-URIs.
 */
@OptIn(UnstableApi::class)
internal class CastMediaItemConverter(private val cache: CastMediaCache) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val media = cache.get(mediaItem)
        val spoken = mediaItem.spokenCastSource != null
        if (media == null) Log.w(TAG, "${mediaItem.mediaId} hat keine Server-Quelle")
        val meta = mediaItem.mediaMetadata
        val metadata = MediaMetadata(if (spoken) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            meta.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            if (spoken) {
                // Autor bzw. Podcast-Name als Untertitel; Dateien eines Buchs haben keine Titel- und Plattennummern.
                meta.artist?.let { putString(MediaMetadata.KEY_SUBTITLE, it.toString()) }
            } else {
                meta.artist?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
                meta.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
                meta.trackNumber?.let { putInt(MediaMetadata.KEY_TRACK_NUMBER, it) }
                meta.discNumber?.let { putInt(MediaMetadata.KEY_DISC_NUMBER, it) }
            }
            media?.coverUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(mediaItem.mediaId)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentUrl(media?.streamUrl.orEmpty())
            .setContentType(media?.mimeType)
            .setMetadata(metadata)
            .apply { meta.durationMs?.let { setStreamDuration(it) } }
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    /** Nur für Einträge, die nicht von uns stammen (z. B. eine fremde Sitzung auf dem Empfänger). */
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val metadata = info?.metadata
        val item = MediaItem.Builder()
            .setMediaId(info?.contentId.orEmpty())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadata?.getString(MediaMetadata.KEY_TITLE))
                    .setArtist(metadata?.getString(MediaMetadata.KEY_ARTIST))
                    .setAlbumTitle(metadata?.getString(MediaMetadata.KEY_ALBUM_TITLE))
                    .build(),
            )
            .build()
        return item.songId?.let { item.buildUpon().setUri(AppUris.song(it)).build() } ?: item
    }

    private companion object {
        const val TAG = "CastMediaItemConverter"
    }
}
