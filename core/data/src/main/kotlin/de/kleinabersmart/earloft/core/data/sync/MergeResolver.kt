package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.data.repository.pushFavoriteToServers
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongGenreEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.Genre
import javax.inject.Inject
import kotlin.math.abs

/**
 * Ordnet einen [RawTrack] einem logischen Song zu (Konzept 6.5).
 *
 * Muss innerhalb einer Datenbank-Transaktion aufgerufen werden.
 */
class MergeResolver @Inject constructor(
    private val mergeDao: MergeDao,
    private val syncDao: SyncDao,
    private val collections: CollectionSourceResolver,
) {

    /**
     * @param syncsFavorites `true` für Server: Deren Stern wird mit dem Favoriten des Songs abgeglichen.
     * @return die `songId`, der die Quelle zugeordnet wurde.
     */
    suspend fun merge(sourceId: String, track: RawTrack, generation: Long, syncsFavorites: Boolean = false): Long {
        val uid = "$sourceId:${track.remoteId}"
        val existing = mergeDao.getSongSource(uid)

        if (existing == null) {
            val songId = resolveSong(resolveAlbum(track), track, sourceId, uid)
            mergeDao.insertSongSource(track.toSongSource(uid, songId, sourceId, generation))
            mergeDao.setAlbumCoverIfMissing(mergeDao.getSong(songId)!!.albumId, uid)
            if (syncsFavorites) {
                mergeFavorite(songId, uid, track.starredAt, previousStarredAt = null, isNewSource = true)
                collections.record(sourceId, track, songId, generation)
            }
            return songId
        }

        if (existing.lastModified != null && existing.lastModified == track.lastModified) {
            mergeDao.markSeen(uid, generation)
            if (syncsFavorites && (existing.starredAt != null) != (track.starredAt != null)) {
                syncDao.setStarredAt(uid, track.starredAt)
                mergeFavorite(existing.songId, uid, track.starredAt, existing.starredAt, isNewSource = false)
            }
            if (syncsFavorites) collections.record(sourceId, track, existing.songId, generation)
            return existing.songId
        }

        // Die Datei hat sich geändert (z. B. neu getaggt).
        val albumId = resolveAlbum(track)
        val songId = if (mergeDao.countSourcesOfSong(existing.songId) == 1) {
            // Einzige Quelle: Song in-place aktualisieren, damit songId (Favoriten, Playlists) erhalten bleibt.
            val song = mergeDao.getSong(existing.songId)!!
            mergeDao.updateSong(track.applyTo(song, albumId))
            linkSongArtists(song.songId, track.artists, replace = true)
            linkSongGenres(song.songId, track.allGenres, replace = true)
            song.songId
        } else {
            // Der Song wird noch von anderen Quellen geliefert: nur diese Quelle neu zuordnen.
            resolveSong(albumId, track, sourceId, uid)
        }
        mergeDao.updateSongSource(track.toSongSource(uid, songId, sourceId, generation))
        // Ist die Datei selbst eine andere, wird ein Download neu geladen. Reine Metadaten-Änderungen
        // (z. B. Album-Cover) zählen nicht – angezeigt wird ohnehin, was in der Datenbank steht.
        if (existing.sizeBytes != track.sizeBytes || existing.mimeType != track.mimeType) mergeDao.requeueDownload(uid)
        mergeDao.setAlbumCoverIfMissing(albumId, uid)
        if (syncsFavorites) {
            mergeFavorite(songId, uid, track.starredAt, existing.starredAt, isNewSource = false)
            collections.record(sourceId, track, songId, generation)
        }
        return songId
    }

    /**
     * Favoriten zwischen Server und Song abgleichen (Konzept 6.4). Reagiert nur auf Änderungen:
     * - Server hat (ent)sternt → Song übernimmt das, andere Server folgen.
     * - Neue Server-Quelle für einen schon favorisierten Song → Stern wird an den Server gesendet,
     *   statt den lokalen Favoriten zu verlieren.
     * Hat der Song noch ungesendete lokale Änderungen, gewinnen diese.
     */
    private suspend fun mergeFavorite(songId: Long, uid: String, starredAt: Long?, previousStarredAt: Long?, isNewSource: Boolean) {
        if (syncDao.hasPendingFavorite(songId)) return
        val isFavorite = mergeDao.getSong(songId)?.isFavorite ?: return
        val starred = starredAt != null
        val serverChanged = if (isNewSource) starred else starred != (previousStarredAt != null)
        when {
            serverChanged && starred != isFavorite -> {
                // Jellyfin/Emby kennen keinen Zeitpunkt (0): dann gilt jetzt, sonst stünde der Favorit ganz unten (1970).
                syncDao.setSongFavorite(songId, starred, starredAt?.takeIf { it > 0 } ?: System.currentTimeMillis())
                syncDao.pushFavoriteToServers(songId, starred, exceptUid = uid)
            }
            isNewSource && isFavorite && !starred -> syncDao.pushFavoriteToServers(songId, favorite = true)
        }
    }

    /**
     * Übernimmt den vollständigen Favoriten-Stand eines Servers ([starred]: `remoteId` → Zeitpunkt), ohne die
     * Songs selbst abzugleichen. Quellen, die der Server nicht (mehr) als favorisiert führt, gelten als entsternt.
     * Wie bei [merge] zählt nur, was sich gegenüber dem zuletzt bekannten Stand geändert hat.
     */
    suspend fun mergeStarred(sourceId: String, starred: Map<String, Long>) {
        for (state in syncDao.getStarStates(sourceId)) {
            val starredAt = starred[state.remoteId]
            if ((state.starredAt != null) == (starredAt != null)) continue
            syncDao.setStarredAt(state.uid, starredAt)
            mergeFavorite(state.songId, state.uid, starredAt, state.starredAt, isNewSource = false)
        }
    }

    private suspend fun resolveArtist(raw: RawArtist): Long {
        raw.mbid?.let { mbid -> mergeDao.findArtistByMbid(mbid)?.let { return it.artistId } }

        val normName = raw.name.mergeKey()
        val byName = mergeDao.findArtistByNormName(normName)
        if (byName != null && !conflicts(byName.mbid, raw.mbid)) {
            if (byName.mbid == null && raw.mbid != null) mergeDao.updateArtist(byName.copy(mbid = raw.mbid))
            return byName.artistId
        }
        return mergeDao.insertArtist(
            ArtistEntity(name = raw.name, sortName = raw.name.sortKey(), normName = normName, mbid = raw.mbid),
        )
    }

    private suspend fun resolveAlbum(track: RawTrack): Long {
        track.mbidAlbum?.let { mbid -> mergeDao.findAlbumByMbid(mbid)?.let { return it.albumId } }

        val candidates = mergeDao.findAlbums(track.albumArtistDisplay.mergeKey(), track.albumTitle.mergeKey())
            .filterNot { conflicts(it.mbid, track.mbidAlbum) }
        val match = candidates.firstOrNull { it.year == track.year }
            ?: candidates.singleOrNull()?.takeIf { it.year == null || track.year == null }

        if (match != null) {
            val filledIn = match.copy(year = match.year ?: track.year, mbid = match.mbid ?: track.mbidAlbum)
            if (filledIn != match) mergeDao.updateAlbum(filledIn)
            return match.albumId
        }

        val albumId = mergeDao.insertAlbum(
            AlbumEntity(
                title = track.albumTitle,
                sortTitle = track.albumTitle.sortKey(),
                normTitle = track.albumTitle.mergeKey(),
                albumArtistDisplay = track.albumArtistDisplay,
                normAlbumArtist = track.albumArtistDisplay.mergeKey(),
                year = track.year,
                isCompilation = track.isCompilation,
                mbid = track.mbidAlbum,
            ),
        )
        track.albumArtists.forEachIndexed { position, artist ->
            mergeDao.insertAlbumArtist(AlbumArtistCrossRef(albumId, resolveArtist(artist), position))
        }
        return albumId
    }

    /**
     * Der Song, zu dem der Track der Quelle [sourceId] (Kennung [uid]) gehört, sonst ein neuer. Gefunden wird nur ein Song, den
     * diese Quelle nicht schon liefert: Zwei Tracks einer Quelle sind zwei Songs, auch mit gleichem Titel und fast gleicher Länge
     * (z. B. „Walk This Way“ von Aerosmith und von Run-DMC auf demselben Album), mit derselben MBID (eine Aufnahme zweimal auf
     * einer Deluxe-Ausgabe) oder an derselben Stelle (zwei „Interlude“ ohne Tracknummer, Review V10).
     */
    private suspend fun resolveSong(albumId: Long, track: RawTrack, sourceId: String, uid: String): Long {
        suspend fun SongEntity.isFree() = !mergeDao.hasOtherSourceFrom(songId, sourceId, uid)

        track.mbidTrack?.let { mbid -> mergeDao.findSongByMbid(albumId, mbid)?.takeIf { it.isFree() }?.let { return it.songId } }

        val normTitle = track.title.mergeKey()
        val match = mergeDao.findSongByPosition(albumId, track.discNumber, track.trackNumber, normTitle)
            ?.takeIf { !conflicts(it.mbid, track.mbidTrack) && it.isFree() }
            ?: mergeDao.findSongsByTitle(albumId, normTitle).firstOrNull {
                !conflicts(it.mbid, track.mbidTrack) && abs(it.durationMs - track.durationMs) < DURATION_TOLERANCE_MS && it.isFree()
            }
        if (match != null) {
            // Wie bei Künstler und Album: Eine Quelle, die die MBID kennt, ergänzt sie für alle.
            if (match.mbid == null && track.mbidTrack != null) mergeDao.updateSong(match.copy(mbid = track.mbidTrack))
            return match.songId
        }

        val songId = mergeDao.insertSong(track.applyTo(null, albumId))
        linkSongArtists(songId, track.artists, replace = false)
        linkSongGenres(songId, track.allGenres, replace = false)
        return songId
    }

    /** Wie bei den Künstlern: Die Genres des Songs sind die der Quelle, die ihn angelegt bzw. zuletzt geändert hat. */
    private suspend fun linkSongGenres(songId: Long, genres: List<String>, replace: Boolean) {
        if (replace) mergeDao.deleteSongGenres(songId)
        val rows = genres.distinctBy(Genre::key).mapIndexed { position, genre -> SongGenreEntity(songId, genre, Genre.key(genre), position) }
        if (rows.isNotEmpty()) mergeDao.insertSongGenres(rows)
    }

    private suspend fun linkSongArtists(songId: Long, artists: List<RawArtist>, replace: Boolean) {
        if (replace) mergeDao.deleteSongArtists(songId)
        artists.forEachIndexed { position, artist ->
            mergeDao.insertSongArtist(SongArtistCrossRef(songId, resolveArtist(artist), position))
        }
    }

    private fun RawTrack.applyTo(song: SongEntity?, albumId: Long): SongEntity {
        val base = song ?: SongEntity(
            albumId = albumId,
            title = title,
            normTitle = title.mergeKey(),
            artistDisplay = artistDisplay,
            durationMs = durationMs,
            dateAdded = dateAdded ?: System.currentTimeMillis(),
        )
        return base.copy(
            albumId = albumId,
            title = title,
            normTitle = title.mergeKey(),
            artistDisplay = artistDisplay,
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMs = durationMs,
            year = year,
            genre = allGenres.firstOrNull(),
            mbid = mbidTrack ?: base.mbid,
        )
    }

    private fun RawTrack.toSongSource(uid: String, songId: Long, sourceId: String, generation: Long) =
        SongSourceEntity(
            uid = uid,
            songId = songId,
            sourceId = sourceId,
            remoteId = remoteId,
            coverRemoteId = coverRemoteId,
            mimeType = mimeType,
            bitRate = bitRate,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            rgTrackGainDb = rgTrackGainDb,
            rgTrackPeak = rgTrackPeak,
            rgAlbumGainDb = rgAlbumGainDb,
            rgAlbumPeak = rgAlbumPeak,
            seenInGeneration = generation,
            starredAt = starredAt,
        )

    /** Zwei unterschiedliche MBIDs bedeuten bewusst verschiedene Einträge. */
    private fun conflicts(a: String?, b: String?) = a != null && b != null && a != b

    private companion object {
        const val DURATION_TOLERANCE_MS = 3_000
    }
}

/** Fällt auf den Rohtext zurück, falls ein Name nur aus Satzzeichen besteht (z. B. „!!!“). */
internal fun String.mergeKey(): String = normalizeForMerge().ifEmpty { trim().lowercase() }
