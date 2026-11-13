package de.kleinabersmart.earloft.cast

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
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.playback.songId
import de.kleinabersmart.earloft.core.playback.spokenCastSource
import org.json.JSONObject

/**
 * `earloft://song/<id>` bzw. Buch-/Folgen-Datei → Server-Stream für den Cast-Empfänger. Die `contentId` ist die Media-ID
 * (`song:<id>`); darüber ordnet Media3 die Einträge des Empfängers wieder den ursprünglichen Items zu,
 * die Warteschlange auf dem Handy behält also ihre `earloft://`-URIs.
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
            // Folge oder Buch: Die Cast-Metadaten kennen den Unterschied nicht, der Player braucht ihn aber (Outro,
            // Sleep-Timer, Weiterspielen), auch wenn die App sich später wieder an die Sitzung hängt.
            .apply { meta.mediaType?.let { setCustomData(JSONObject().put(KEY_MEDIA_TYPE, it)) } }
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    /**
     * Für Einträge, die Media3 keinem Item der App zuordnen kann: eine fremde Sitzung auf dem Empfänger, oder die App hängt
     * sich nach einem Neustart wieder an die eigene. Dann kommt die Art (Folge/Buch) aus [KEY_MEDIA_TYPE].
     */
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val metadata = info?.metadata
        val mediaType = info?.customData?.optInt(KEY_MEDIA_TYPE, -1)?.takeIf { it >= 0 }
        val item = MediaItem.Builder()
            .setMediaId(info?.contentId.orEmpty())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadata?.getString(MediaMetadata.KEY_TITLE))
                    // Bücher und Folgen tragen Autor bzw. Podcast als Untertitel.
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

        /** `customData` des Eintrags: `MediaMetadata.mediaType` von Media3. */
        const val KEY_MEDIA_TYPE = "earloftMediaType"
    }
}
