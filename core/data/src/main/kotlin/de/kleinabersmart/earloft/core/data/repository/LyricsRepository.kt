package de.kleinabersmart.earloft.core.data.repository

import android.util.Log
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.dao.LyricsDao
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.entity.LyricsEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.datasource.LocalLyrics
import de.kleinabersmart.earloft.core.datasource.RemoteLyrics
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.LyricsCodec
import de.kleinabersmart.earloft.core.model.LyricsKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Lyrics and whether they come from LRCLIB. */
data class SongLyrics(val lyrics: Lyrics, val fromLrclib: Boolean = false)

/**
 * Lyrics: first embedded lyrics of local files, then the server sources of a song (concept 6.3), finally, if
 * enabled, LRCLIB – also for a song for which the servers only have plain lyrics. Found lyrics stay
 * stored; "no lyrics" is only remembered for [NO_LYRICS_TTL_MS], so lyrics added later show up. If no
 * source is reachable, nothing is stored, and plain lyrics stay unstored as long as a source hasn't
 * responded (it might have synced ones).
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val lyricsDao: LyricsDao,
    private val mergeDao: MergeDao,
    private val sources: MusicDataSources,
    private val settings: LyricsSettings,
    private val lrclib: LrclibClient,
) {
    /** Storing and toggling the setting are mutually exclusive: a running query doesn't survive being switched off. */
    private val storeLock = Mutex()

    suspend fun lyrics(songId: Long): Lyrics = songLyrics(songId).lyrics

    /** Like [lyrics], plus whether the lyrics come from LRCLIB (the display then names the source). */
    suspend fun songLyrics(songId: Long): SongLyrics {
        val cached = lyricsDao.get(songId)
        if (cached != null && (cached.kind != LyricsKind.NONE || System.currentTimeMillis() - cached.fetchedAt < NO_LYRICS_TTL_MS)) {
            return SongLyrics(LyricsCodec.decode(cached.kind, cached.content), fromLrclib = cached.origin == LRCLIB)
        }

        val song = mergeDao.getSong(songId) ?: return SongLyrics(Lyrics.None)
        val embedded = embeddedLyrics(songId)
        if (embedded != Lyrics.None) {
            val (kind, content) = LyricsCodec.encode(embedded)
            lyricsDao.upsert(LyricsEntity(songId, kind, content, EMBEDDED, System.currentTimeMillis()))
            return SongLyrics(embedded)
        }

        val online = settings.onlineLyrics.first()
        val serverSources = lyricsDao.getServerSources(songId)
        if (serverSources.isEmpty() && !online) {
            lyricsDao.upsert(LyricsEntity(songId, LyricsKind.NONE, "", origin = "", fetchedAt = System.currentTimeMillis()))
            return SongLyrics(Lyrics.None)
        }

        var best: Lyrics = Lyrics.None
        var origin = ""
        var failed = false
        for (source in serverSources) {
            val provider = sources[source.sourceId] as? RemoteLyrics ?: continue
            val found = try {
                provider.lyrics(source.remoteId, song.artistDisplay, song.title)
            } catch (e: IOException) {
                Log.w(TAG, "Lyrics from ${source.sourceId} not available", e)
                failed = true
                continue
            }
            if (found is Lyrics.Synced) {
                best = found
                origin = source.sourceId
                break
            }
            if (found is Lyrics.Plain && best == Lyrics.None) {
                best = found
                origin = source.sourceId
            }
        }

        val serverBest = best
        val serverOrigin = origin
        if (online && best !is Lyrics.Synced) {
            val found = try {
                onlineLyrics(song)
            } catch (e: IOException) {
                Log.w(TAG, "Lyrics from LRCLIB not available", e)
                failed = true
                Lyrics.None
            }
            // Synced beats plain lyrics from the server; otherwise the server's stay.
            if (found is Lyrics.Synced || (found is Lyrics.Plain && best == Lyrics.None)) {
                best = found
                origin = LRCLIB
            }
        }

        return storeLock.withLock {
            // Switched off during the query: the lyrics from LRCLIB are neither stored nor shown.
            if (origin == LRCLIB && !settings.onlineLyrics.first()) {
                best = serverBest
                origin = serverOrigin
            }
            if (best == Lyrics.None) {
                if (failed) return@withLock SongLyrics(Lyrics.Unavailable)
                lyricsDao.upsert(LyricsEntity(songId, LyricsKind.NONE, "", origin = "", fetchedAt = System.currentTimeMillis()))
                return@withLock SongLyrics(Lyrics.None)
            }
            // A source didn't respond: plain lyrics are returned without storing, the next query tries again.
            if (failed && best !is Lyrics.Synced) return@withLock SongLyrics(best, fromLrclib = origin == LRCLIB)
            val (kind, content) = LyricsCodec.encode(best)
            lyricsDao.upsert(LyricsEntity(songId, kind, content, origin, System.currentTimeMillis()))
            SongLyrics(best, fromLrclib = origin == LRCLIB)
        }
    }

    /**
     * When enabled, missing and plain lyrics from the servers are forgotten, so LRCLIB can fill them in the next time they are opened;
     * when disabled, those from LRCLIB, which the servers and files then supply alone again.
     */
    suspend fun setOnlineLyrics(enabled: Boolean) {
        storeLock.withLock {
            settings.setOnlineLyrics(enabled)
            if (enabled) lyricsDao.deleteUnsyncedExcept(EMBEDDED) else lyricsDao.deleteByOrigin(LRCLIB)
        }
    }

    /** Queried with the displayed artist ("A feat. B") and, if different, the first one alone. */
    private suspend fun onlineLyrics(song: SongEntity): Lyrics {
        val artists = listOfNotNull(song.artistDisplay, lyricsDao.getFirstArtistName(song.songId))
        return lrclib.find(song.title, artists, song.durationMs)
    }

    /** Lyrics embedded in the file of local sources; they take precedence over those of the servers. */
    private suspend fun embeddedLyrics(songId: Long): Lyrics {
        for (source in lyricsDao.getLocalSources(songId)) {
            val provider = sources[source.sourceId] as? LocalLyrics ?: continue
            val found = provider.embeddedLyrics(source.remoteId)
            if (found != Lyrics.None) return found
        }
        return Lyrics.None
    }

    private companion object {
        const val TAG = "LyricsRepository"
        const val EMBEDDED = "embedded"
        const val LRCLIB = "lrclib"
        val NO_LYRICS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
