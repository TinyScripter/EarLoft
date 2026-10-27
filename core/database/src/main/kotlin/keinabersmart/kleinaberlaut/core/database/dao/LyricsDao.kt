package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import keinabersmart.kleinaberlaut.core.database.entity.LyricsEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity

/** Lyrics-Cache (inkl. Negativ-Cache `NONE`) und die Server-Quellen, die danach gefragt werden. */
@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: Long): LyricsEntity?

    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Query(
        """
        SELECT ss.* FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.type = 'SUBSONIC'
        ORDER BY src.priority DESC
        """,
    )
    suspend fun getServerSources(songId: Long): List<SongSourceEntity>
}
