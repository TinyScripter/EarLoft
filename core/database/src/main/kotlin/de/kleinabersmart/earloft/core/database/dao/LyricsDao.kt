package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import de.kleinabersmart.earloft.core.database.entity.LyricsEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity

/** Lyrics cache (incl. negative cache `NONE`) and the server sources that are asked for them. */
@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: Long): LyricsEntity?

    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Query(
        """
        SELECT ss.* FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.type = 'LOCAL'
        """,
    )
    suspend fun getLocalSources(songId: Long): List<SongSourceEntity>

    /** Only enabled servers: a disabled one is not asked, just as it isn't synced. */
    @Query(
        """
        SELECT ss.* FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.type NOT IN $NOT_SERVER_TYPES AND src.enabled = 1
        ORDER BY src.priority DESC
        """,
    )
    suspend fun getServerSources(songId: Long): List<SongSourceEntity>

    /** First artist of the song (without "feat."), for searching the internet. */
    @Query(
        """
        SELECT a.name FROM song_artists sa JOIN artists a ON a.artistId = sa.artistId
        WHERE sa.songId = :songId ORDER BY sa.position LIMIT 1
        """,
    )
    suspend fun getFirstArtistName(songId: Long): String?

    @Query("DELETE FROM lyrics WHERE origin = :origin")
    suspend fun deleteByOrigin(origin: String)

    /** Forgets missing and static lyrics except [keepOrigin] so a new source can fill them in. */
    @Query("DELETE FROM lyrics WHERE kind != 'SYNCED' AND origin != :keepOrigin")
    suspend fun deleteUnsyncedExcept(keepOrigin: String)
}
