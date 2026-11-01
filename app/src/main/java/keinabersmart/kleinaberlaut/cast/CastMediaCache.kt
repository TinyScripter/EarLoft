package keinabersmart.kleinaberlaut.cast

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import keinabersmart.kleinaberlaut.core.data.repository.CastMedia
import keinabersmart.kleinaberlaut.core.data.repository.CastFormats
import keinabersmart.kleinaberlaut.core.data.repository.CastMediaResolver
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.playback.SpokenCastSource
import keinabersmart.kleinaberlaut.core.playback.songId
import keinabersmart.kleinaberlaut.core.playback.spokenCastSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast-Adressen der Songs in der Warteschlange. Der [CastMediaItemConverter] von Media3 muss synchron
 * liefern; deshalb wird vorher aufgelöst ([prepare]) – beim Start einer Cast-Sitzung für die ganze
 * Warteschlange, beim Einreihen für die neuen Songs. Nur im Speicher, weil die Adressen Zugangsdaten enthalten.
 */
@Singleton
class CastMediaCache @Inject constructor(
    private val resolver: CastMediaResolver,
    private val audiobookshelf: AudiobookshelfServers,
) {
    /** `media == null`: Song lässt sich nicht casten (nur auf dem Handy). */
    private class Entry(val media: CastMedia?)

    private val entries = ConcurrentHashMap<Long, Entry>()

    suspend fun prepare(songIds: Collection<Long>) {
        val missing = songIds.filterNot(entries::containsKey).distinct()
        if (missing.isEmpty()) return
        val resolved = resolver.resolve(missing)
        missing.forEach { entries[it] = Entry(resolved[it]) }
    }

    /** Synchron für Media3; was nicht vorbereitet wurde, wird blockierend nachgeladen (sollte selten sein). */
    fun prepareBlocking(songIds: Collection<Long>) {
        if (songIds.all(entries::containsKey)) return
        Log.w(TAG, "Cast-Adressen nicht vorbereitet, lade ${songIds.size} Songs blockierend")
        runBlocking(Dispatchers.IO) { prepare(songIds) }
    }

    fun get(songId: Long): CastMedia? {
        prepareBlocking(listOf(songId))
        return entries[songId]?.media
    }

    fun isCastable(songId: Long): Boolean = get(songId) != null

    /** Song oder Buch/Folge; `null`, wenn der Empfänger es nicht laden kann (z. B. nur auf dem Handy). */
    fun get(item: MediaItem): CastMedia? =
        item.songId?.let(::get) ?: item.spokenCastSource?.let(::spoken)

    fun isCastable(item: MediaItem): Boolean = get(item) != null

    /**
     * Bücher und Folgen brauchen keine Vorbereitung außer dem Schlüssel des Audiobookshelf-Servers: Der Empfänger kann
     * keine Header senden, deshalb hängt er als `token` an der Adresse (nur im Speicher, nie gespeichert oder geloggt).
     */
    private fun spoken(source: SpokenCastSource): CastMedia? {
        val url = Uri.parse(source.url)
        if (url.scheme != "http" && url.scheme != "https") return null
        val apiKey = source.absServerId?.let { id ->
            runBlocking(Dispatchers.IO) { audiobookshelf.server(id) }?.apiKey ?: return null
        }
        fun withKey(address: String): String =
            if (apiKey == null) address else Uri.parse(address).buildUpon().appendQueryParameter("token", apiKey).build().toString()
        return CastMedia(
            streamUrl = withKey(source.url),
            mimeType = CastFormats.playableMimeType(source.mimeType) ?: guessMimeType(url.lastPathSegment),
            coverUrl = source.coverUrl?.let(::withKey),
        )
    }

    /** Feeds nennen den Typ oft nicht oder falsch (`audio/x-m4b`); dann entscheidet die Endung. */
    private fun guessMimeType(fileName: String?): String = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    /** Zu Beginn jeder Sitzung: Server oder Passwort können sich seit der letzten geändert haben. */
    fun clear() = entries.clear()

    private companion object {
        const val TAG = "CastMediaCache"
    }
}
