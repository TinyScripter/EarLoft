package de.kleinabersmart.kleinaberlaut.core.data.repository

import android.util.Log
import de.kleinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import de.kleinabersmart.kleinaberlaut.core.database.dao.LyricsDao
import de.kleinabersmart.kleinaberlaut.core.database.dao.MergeDao
import de.kleinabersmart.kleinaberlaut.core.database.entity.LyricsEntity
import de.kleinabersmart.kleinaberlaut.core.datasource.LocalLyrics
import de.kleinabersmart.kleinaberlaut.core.datasource.RemoteLyrics
import de.kleinabersmart.kleinaberlaut.core.model.Lyrics
import de.kleinabersmart.kleinaberlaut.core.model.LyricsCodec
import de.kleinabersmart.kleinaberlaut.core.model.LyricsKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Songtexte: zuerst eingebettete Texte lokaler Dateien, dann die Server-Quellen eines Songs (Konzept 6.3). Gefundene Texte bleiben gespeichert; „kein Text“ wird nur [NO_LYRICS_TTL_MS] gemerkt,
 * damit ein später ergänzter Text auftaucht. Ist keine Quelle erreichbar, wird nichts gespeichert.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val lyricsDao: LyricsDao,
    private val mergeDao: MergeDao,
    private val sources: MusicDataSources,
) {
    suspend fun lyrics(songId: Long): Lyrics {
        val cached = lyricsDao.get(songId)
        if (cached != null && (cached.kind != LyricsKind.NONE || System.currentTimeMillis() - cached.fetchedAt < NO_LYRICS_TTL_MS)) {
            return LyricsCodec.decode(cached.kind, cached.content)
        }

        val song = mergeDao.getSong(songId) ?: return Lyrics.None
        val embedded = embeddedLyrics(songId)
        if (embedded != Lyrics.None) {
            val (kind, content) = LyricsCodec.encode(embedded)
            lyricsDao.upsert(LyricsEntity(songId, kind, content, EMBEDDED, System.currentTimeMillis()))
            return embedded
        }

        val serverSources = lyricsDao.getServerSources(songId)
        if (serverSources.isEmpty()) {
            lyricsDao.upsert(LyricsEntity(songId, LyricsKind.NONE, "", origin = "", fetchedAt = System.currentTimeMillis()))
            return Lyrics.None
        }

        var best: Lyrics = Lyrics.None
        var origin = ""
        var failed = false
        for (source in serverSources) {
            val provider = sources[source.sourceId] as? RemoteLyrics ?: continue
            val found = try {
                provider.lyrics(source.remoteId, song.artistDisplay, song.title)
            } catch (e: IOException) {
                Log.w(TAG, "Lyrics von ${source.sourceId} nicht abrufbar", e)
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

        if (best == Lyrics.None) {
            if (failed) return Lyrics.Unavailable
            lyricsDao.upsert(LyricsEntity(songId, LyricsKind.NONE, "", origin = "", fetchedAt = System.currentTimeMillis()))
            return Lyrics.None
        }
        val (kind, content) = LyricsCodec.encode(best)
        lyricsDao.upsert(LyricsEntity(songId, kind, content, origin, System.currentTimeMillis()))
        return best
    }

    /** In der Datei eingebettete Texte lokaler Quellen; sie haben Vorrang vor denen der Server. */
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
        val NO_LYRICS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
