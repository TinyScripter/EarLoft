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

/** Ein Songtext und ob er von LRCLIB stammt. */
data class SongLyrics(val lyrics: Lyrics, val fromLrclib: Boolean = false)

/**
 * Songtexte: zuerst eingebettete Texte lokaler Dateien, dann die Server-Quellen eines Songs (Konzept 6.3), zuletzt, wenn
 * eingeschaltet, LRCLIB – auch für einen Song, zu dem die Server nur einen statischen Text haben. Gefundene Texte bleiben
 * gespeichert; „kein Text“ wird nur [NO_LYRICS_TTL_MS] gemerkt, damit ein später ergänzter Text auftaucht. Ist keine
 * Quelle erreichbar, wird nichts gespeichert, und ein statischer Text bleibt ungespeichert, solange eine Quelle nicht
 * geantwortet hat (sie könnte einen synchronisierten haben).
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val lyricsDao: LyricsDao,
    private val mergeDao: MergeDao,
    private val sources: MusicDataSources,
    private val settings: LyricsSettings,
    private val lrclib: LrclibClient,
) {
    /** Speichern und Umschalten der Einstellung schließen sich aus: Eine laufende Abfrage überlebt das Ausschalten nicht. */
    private val storeLock = Mutex()

    suspend fun lyrics(songId: Long): Lyrics = songLyrics(songId).lyrics

    /** Wie [lyrics], dazu ob der Text von LRCLIB stammt (die Anzeige nennt dann die Quelle). */
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

        val serverBest = best
        val serverOrigin = origin
        if (online && best !is Lyrics.Synced) {
            val found = try {
                onlineLyrics(song)
            } catch (e: IOException) {
                Log.w(TAG, "Lyrics von LRCLIB nicht abrufbar", e)
                failed = true
                Lyrics.None
            }
            // Synchron schlägt einen statischen Text vom Server; sonst bleibt der des Servers.
            if (found is Lyrics.Synced || (found is Lyrics.Plain && best == Lyrics.None)) {
                best = found
                origin = LRCLIB
            }
        }

        return storeLock.withLock {
            // Während der Abfrage ausgeschaltet: Der Text von LRCLIB wird weder gespeichert noch angezeigt.
            if (origin == LRCLIB && !settings.onlineLyrics.first()) {
                best = serverBest
                origin = serverOrigin
            }
            if (best == Lyrics.None) {
                if (failed) return@withLock SongLyrics(Lyrics.Unavailable)
                lyricsDao.upsert(LyricsEntity(songId, LyricsKind.NONE, "", origin = "", fetchedAt = System.currentTimeMillis()))
                return@withLock SongLyrics(Lyrics.None)
            }
            // Eine Quelle hat nicht geantwortet: Ein statischer Text kommt ohne Speichern, die nächste Abfrage versucht es neu.
            if (failed && best !is Lyrics.Synced) return@withLock SongLyrics(best, fromLrclib = origin == LRCLIB)
            val (kind, content) = LyricsCodec.encode(best)
            lyricsDao.upsert(LyricsEntity(songId, kind, content, origin, System.currentTimeMillis()))
            SongLyrics(best, fromLrclib = origin == LRCLIB)
        }
    }

    /**
     * Beim Einschalten werden fehlende und statische Texte der Server vergessen, damit LRCLIB sie beim nächsten Öffnen
     * ergänzen kann; beim Ausschalten die von LRCLIB, die Server und Dateien dann wieder allein liefern.
     */
    suspend fun setOnlineLyrics(enabled: Boolean) {
        storeLock.withLock {
            settings.setOnlineLyrics(enabled)
            if (enabled) lyricsDao.deleteUnsyncedExcept(EMBEDDED) else lyricsDao.deleteByOrigin(LRCLIB)
        }
    }

    /** Gefragt wird mit dem angezeigten Interpreten („A feat. B“) und, falls anders, dem ersten allein. */
    private suspend fun onlineLyrics(song: SongEntity): Lyrics {
        val artists = listOfNotNull(song.artistDisplay, lyricsDao.getFirstArtistName(song.songId))
        return lrclib.find(song.title, artists, song.durationMs)
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
        const val LRCLIB = "lrclib"
        val NO_LYRICS_TTL_MS = TimeUnit.DAYS.toMillis(7)
    }
}
