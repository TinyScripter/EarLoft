package de.kleinabersmart.earloft.cast

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast addresses of the songs in the queue. Media3's [CastMediaItemConverter] has to return
 * synchronously; that's why they are resolved in advance ([prepare]) – for the whole queue when a cast session
 * starts, for the new songs when enqueuing. Kept in memory only, because the addresses contain credentials.
 * Likewise the keys of the Audiobookshelf servers for books and episodes ([prepareServerKeys]).
 * If the receiver can't reach a music server, that server stays excluded until the session ends ([exclude]).
 */
@Singleton
class CastMediaCache @Inject constructor(
    private val resolver: CastMediaResolver,
    private val audiobookshelf: AudiobookshelfServers,
    private val phone: PhoneCastServer,
) {
    /** `media == null`: the song can't be cast (only on the phone). */
    private class Entry(val media: CastMedia?)

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** Servers the receiver couldn't reach in this session. */
    private val excluded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Key per Audiobookshelf server; `null` until loaded. */
    @Volatile
    private var serverKeys: Map<String, String>? = null

    /**
     * With OpenID sign-in the access token is valid for at most an hour and the receiver can't refresh it: it gets
     * one that is valid for at least another half hour (concept 12.11, known risk with long titles).
     */
    suspend fun prepareServerKeys() {
        serverKeys = audiobookshelf.allIncludingDisabled().associate { it.serverId to (audiobookshelf.freshToken(it.serverId) ?: it.token) }
    }

    /** Changes with [clear] and [exclude]: a [prepare] that started before must not write its stale result afterwards (review2 J78). */
    private val generation = AtomicInteger()

    suspend fun prepare(songIds: Collection<Long>) {
        val missing = songIds.filterNot(entries::containsKey).distinct()
        if (missing.isEmpty()) return
        val started = generation.get()
        val resolved = resolver.resolve(missing, excluded.toSet())
        if (generation.get() != started) return
        missing.forEach { entries[it] = Entry(resolved[it]) }
    }

    /**
     * The receiver can't reach [sourceId]: [prepare] re-resolves songs from there using the next server.
     * @return `false` if the server was already excluded.
     */
    fun exclude(sourceId: String): Boolean {
        if (!excluded.add(sourceId)) return false
        generation.incrementAndGet()
        entries.entries.removeIf { it.value.media?.sourceId == sourceId }
        return true
    }

    /** Synchronous for Media3; anything not prepared is loaded blocking (should be rare). */
    fun prepareBlocking(songIds: Collection<Long>) {
        if (songIds.all(entries::containsKey)) return
        Log.w(TAG, "Cast addresses not prepared, loading ${songIds.size} songs blocking")
        runBlocking(Dispatchers.IO) { prepare(songIds) }
    }

    fun get(songId: Long): CastMedia? {
        prepareBlocking(listOf(songId))
        return entries[songId]?.media
    }

    fun isCastable(songId: Long): Boolean = get(songId) != null

    /** Song or book/episode; `null` if the receiver can't load it (e.g. only on the phone). */
    fun get(item: MediaItem): CastMedia? =
        item.songId?.let(::get) ?: item.spokenCastSource?.let(::spoken)

    fun isCastable(item: MediaItem): Boolean = get(item) != null

    /**
     * Books and episodes need no preparation other than the Audiobookshelf server's key: the receiver can't
     * send headers, so it is appended to the address as `token` (in memory only, never stored or logged).
     */
    private fun spoken(source: SpokenCastSource): CastMedia? {
        val url = source.url.toUri()
        if (url.scheme == "content" || url.scheme == "file") return fromPhone(url, source)
        if (url.scheme != "http" && url.scheme != "https") return null
        val apiKey = source.absServerId?.let { id -> serverKeys()[id] ?: return null }
        fun withKey(address: String): String =
            if (apiKey == null) address else address.toUri().buildUpon().appendQueryParameter("token", apiKey).build().toString()
        return CastMedia(
            streamUrl = withKey(source.url),
            mimeType = CastFormats.playableMimeType(source.mimeType) ?: guessMimeType(url.lastPathSegment),
            // Audiobookshelf serves covers without authentication (checked 2026-11-08: cover 200, book data 401). No key,
            // because cover addresses in the cast metadata are also loaded by other devices (Google Home, media controls of other phones).
            coverUrl = source.coverUrl,
        )
    }

    /**
     * Like [prepareBlocking]: normally the keys are already loaded when the session starts. Otherwise the stored ones,
     * without refreshing: that would go over the network (with OpenID up to the connection timeout per server) and block the main thread
     * (review D10). A token that is barely valid is replaced as soon as [prepareServerKeys] has finished.
     */
    private fun serverKeys(): Map<String, String> = serverKeys ?: run {
        Log.w(TAG, "Server keys not prepared, using the stored ones")
        val stored = runBlocking(Dispatchers.IO) { audiobookshelf.allIncludingDisabled().associate { it.serverId to it.token } }
        // Only if [prepareServerKeys] hasn't finished in the meantime.
        serverKeys ?: stored.also { serverKeys = it }
    }

    /**
     * Book or episode from the device: the phone serves the file and cover itself (Chromecast stage 2). Only formats the
     * receiver knows (nobody can transcode here); not at all without an address on the Wi-Fi. Like a server, the phone can
     * be excluded if the receiver can't reach it.
     */
    private fun fromPhone(uri: Uri, source: SpokenCastSource): CastMedia? {
        if (PhoneCastServer.SOURCE_ID in excluded) return null
        val mimeType = CastFormats.playableMimeType(source.mimeType) ?: return null
        val url = phone.fileUrl(uri, mimeType) ?: return null
        return CastMedia(url, mimeType, source.coverUrl ?: source.bookId?.let(phone::bookCoverUrl), PhoneCastServer.SOURCE_ID)
    }

    /** Feeds often omit the type or get it wrong (`audio/x-m4b`); then the file extension decides. */
    private fun guessMimeType(fileName: String?): String = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    /** At the start of every session: servers or passwords may have changed since the last one. */
    fun clear() {
        generation.incrementAndGet()
        entries.clear()
        excluded.clear()
        serverKeys = null
    }

    private companion object {
        const val TAG = "CastMediaCache"
    }
}
