package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import kotlinx.coroutines.flow.Flow

/** Podcast-Abos; ihre Folgen sind `books`-Zeilen mit `kind = 'EPISODE'` und `serverId = feedId` (Konzept 12.1). */
@Dao
abstract class PodcastDao {

    @Query("SELECT * FROM podcast_feeds ORDER BY title COLLATE NOCASE")
    abstract fun observeFeeds(): Flow<List<PodcastFeedEntity>>

    @Query("SELECT * FROM podcast_feeds")
    abstract suspend fun feeds(): List<PodcastFeedEntity>

    @Query("SELECT * FROM podcast_feeds WHERE feedId = :feedId")
    abstract suspend fun feed(feedId: String): PodcastFeedEntity?

    @Query("SELECT * FROM podcast_feeds WHERE feedId = :feedId")
    abstract fun observeFeed(feedId: String): Flow<PodcastFeedEntity?>

    @Query("SELECT * FROM podcast_feeds WHERE url = :url")
    abstract suspend fun feedByUrl(url: String): PodcastFeedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFeed(feed: PodcastFeedEntity)

    @Query("UPDATE podcast_feeds SET autoDownload = :enabled WHERE feedId = :feedId")
    abstract suspend fun setAutoDownload(feedId: String, enabled: Boolean)

    @Query("DELETE FROM podcast_feeds WHERE feedId = :feedId")
    abstract suspend fun deleteFeedRow(feedId: String)

    @Query("DELETE FROM books WHERE serverId = :feedId")
    abstract suspend fun deleteEpisodesOf(feedId: String)

    /** Entfernt das Abo samt Folgen und deren Fortschritt (Fremdschlüssel). */
    @Transaction
    open suspend fun deleteFeed(feedId: String) {
        deleteEpisodesOf(feedId)
        deleteFeedRow(feedId)
    }

    @Query("SELECT * FROM books WHERE kind = 'EPISODE' ORDER BY addedAt DESC")
    abstract fun observeEpisodes(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE kind = 'EPISODE' AND serverId = :feedId ORDER BY addedAt DESC")
    abstract fun observeEpisodesOf(feedId: String): Flow<List<BookEntity>>

    @Query("SELECT remoteId FROM books WHERE serverId = :feedId")
    abstract suspend fun knownGuids(feedId: String): List<String>

    @Query("DELETE FROM book_tracks WHERE bookId = :bookId")
    abstract suspend fun deleteTracks(bookId: Long)

    @Insert
    abstract suspend fun insertTracks(tracks: List<BookTrackEntity>)

    /** Die Audiodatei einer Folge (genau ein Titel); Kapitel bleiben unberührt. */
    @Transaction
    open suspend fun setTrack(track: BookTrackEntity) {
        deleteTracks(track.bookId)
        insertTracks(listOf(track))
    }

    /**
     * Folgen, die der letzte Abgleich nicht mehr gesehen hat, fallen weg – außer angefangenen: wer eine Folge schon
     * hört, behält sie auch dann, wenn der Feed nur die neuesten führt.
     */
    @Query(
        "DELETE FROM books WHERE serverId = :feedId AND seenInGeneration < :generation " +
            "AND bookId NOT IN (SELECT bookId FROM book_progress WHERE positionMs > 0)",
    )
    abstract suspend fun deleteUnseenEpisodes(feedId: String, generation: Long)
}
