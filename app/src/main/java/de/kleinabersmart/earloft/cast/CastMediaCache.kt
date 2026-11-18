package de.kleinabersmart.earloft.cast

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import de.kleinabersmart.earloft.core.data.repository.CastMedia
import de.kleinabersmart.earloft.core.data.repository.CastFormats
import de.kleinabersmart.earloft.core.data.repository.CastMediaResolver
import de.kleinabersmart.earloft.core.data.repository.PhoneCastServer
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.playback.SpokenCastSource
import de.kleinabersmart.earloft.core.playback.songId
import de.kleinabersmart.earloft.core.playback.spokenCastSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast-Adressen der Songs in der Warteschlange. Der [CastMediaItemConverter] von Media3 muss synchron
 * liefern; deshalb wird vorher aufgelöst ([prepare]) – beim Start einer Cast-Sitzung für die ganze
 * Warteschlange, beim Einreihen für die neuen Songs. Nur im Speicher, weil die Adressen Zugangsdaten enthalten.
 * Ebenso die Schlüssel der Audiobookshelf-Server für Bücher und Folgen ([prepareServerKeys]).
 * Erreicht der Empfänger einen Musikserver nicht, bleibt der bis zum Ende der Sitzung außen vor ([exclude]).
 */
@Singleton
class CastMediaCache @Inject constructor(
    private val resolver: CastMediaResolver,
    private val audiobookshelf: AudiobookshelfServers,
    private val phone: PhoneCastServer,
) {
    /** `media == null`: Song lässt sich nicht casten (nur auf dem Handy). */
    private class Entry(val media: CastMedia?)

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** Server, die der Empfänger in dieser Sitzung nicht erreicht hat. */
    private val excluded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Schlüssel je Audiobookshelf-Server; `null`, solange nicht geladen. */
    @Volatile
    private var serverKeys: Map<String, String>? = null

    /**
     * Bei Anmeldung per OpenID gilt der Access-Token höchstens eine Stunde und der Empfänger kann ihn nicht erneuern: Er bekommt
     * einen, der noch mindestens eine halbe Stunde gilt (Konzept 12.11, bekanntes Risiko bei langen Titeln).
     */
    suspend fun prepareServerKeys() {
        serverKeys = audiobookshelf.allIncludingDisabled().associate { it.serverId to (audiobookshelf.freshToken(it.serverId) ?: it.token) }
    }

    suspend fun prepare(songIds: Collection<Long>) {
        val missing = songIds.filterNot(entries::containsKey).distinct()
        if (missing.isEmpty()) return
        val resolved = resolver.resolve(missing, excluded.toSet())
        missing.forEach { entries[it] = Entry(resolved[it]) }
    }

    /**
     * Der Empfänger erreicht [sourceId] nicht: Songs von dort löst [prepare] neu auf, mit dem nächsten Server.
     * @return `false`, wenn der Server schon ausgeschlossen war.
     */
    fun exclude(sourceId: String): Boolean {
        if (!excluded.add(sourceId)) return false
        entries.entries.removeIf { it.value.media?.sourceId == sourceId }
        return true
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
        if (url.scheme == "content" || url.scheme == "file") return fromPhone(url, source)
        if (url.scheme != "http" && url.scheme != "https") return null
        val apiKey = source.absServerId?.let { id -> serverKeys()[id] ?: return null }
        fun withKey(address: String): String =
            if (apiKey == null) address else Uri.parse(address).buildUpon().appendQueryParameter("token", apiKey).build().toString()
        return CastMedia(
            streamUrl = withKey(source.url),
            mimeType = CastFormats.playableMimeType(source.mimeType) ?: guessMimeType(url.lastPathSegment),
            // Cover liefert Audiobookshelf ohne Anmeldung (geprüft 2026-11-08: Cover 200, Buchdaten 401). Ohne Schlüssel,
            // denn Cover-Adressen der Cast-Metadaten laden auch andere Geräte (Google Home, Mediensteuerung anderer Handys).
            coverUrl = source.coverUrl,
        )
    }

    /**
     * Wie [prepareBlocking]: Normalerweise sind die Schlüssel beim Start der Sitzung schon geladen. Sonst die gespeicherten,
     * ohne zu erneuern: Das ginge übers Netz (bei OpenID bis zum Verbindungs-Timeout je Server) und blockierte den Main-Thread
     * (Review D10). Ein knapp gültiger Token wird ersetzt, sobald [prepareServerKeys] fertig ist.
     */
    private fun serverKeys(): Map<String, String> = serverKeys ?: run {
        Log.w(TAG, "Server-Schlüssel nicht vorbereitet, nehme die gespeicherten")
        val stored = runBlocking(Dispatchers.IO) { audiobookshelf.allIncludingDisabled().associate { it.serverId to it.token } }
        // Nur, wenn [prepareServerKeys] nicht inzwischen fertig ist.
        serverKeys ?: stored.also { serverKeys = it }
    }

    /**
     * Buch bzw. Folge vom Gerät: Das Handy liefert Datei und Cover selbst aus (Chromecast Etappe 2). Nur Formate, die der
     * Empfänger kennt (umwandeln kann hier niemand); ohne Adresse im WLAN gar nicht. Wie ein Server lässt sich das Handy
     * ausschließen, wenn der Empfänger es nicht erreicht.
     */
    private fun fromPhone(uri: Uri, source: SpokenCastSource): CastMedia? {
        if (PhoneCastServer.SOURCE_ID in excluded) return null
        val mimeType = CastFormats.playableMimeType(source.mimeType) ?: return null
        val url = phone.fileUrl(uri, mimeType) ?: return null
        return CastMedia(url, mimeType, source.coverUrl ?: source.bookId?.let(phone::bookCoverUrl), PhoneCastServer.SOURCE_ID)
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
    fun clear() {
        entries.clear()
        excluded.clear()
        serverKeys = null
    }

    private companion object {
        const val TAG = "CastMediaCache"
    }
}
