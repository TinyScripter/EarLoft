package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import de.kleinabersmart.earloft.core.database.entity.UserArdBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.UserCollectionFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeDownloadEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeStarEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import de.kleinabersmart.earloft.core.database.entity.UserListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.UserListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.UserPodcastEntity
import de.kleinabersmart.earloft.core.database.entity.UserServerEntity
import de.kleinabersmart.earloft.core.database.entity.UserSpokenServerEntity

/** Reading and changing the backed-up user data in `user.db`. */
@Dao
abstract class UserDataDao {

    @Query("SELECT * FROM servers")
    abstract suspend fun servers(): List<UserServerEntity>

    @Query("SELECT * FROM spoken_servers")
    abstract suspend fun spokenServers(): List<UserSpokenServerEntity>

    @Query("SELECT * FROM podcasts")
    abstract suspend fun podcasts(): List<UserPodcastEntity>

    @Query("SELECT * FROM listening_stats")
    abstract suspend fun listeningStats(): List<UserListeningStatEntity>

    @Query("SELECT * FROM listening_days")
    abstract suspend fun listeningDays(): List<UserListeningDayEntity>

    @Query("SELECT * FROM book_progress")
    abstract suspend fun progress(): List<UserBookProgressEntity>

    @Query("SELECT * FROM bookmarks")
    abstract suspend fun bookmarks(): List<UserBookmarkEntity>

    @Query("SELECT * FROM playlists")
    abstract suspend fun playlists(): List<UserPlaylistEntity>

    @Query("SELECT * FROM playlist_entries")
    abstract suspend fun playlistEntries(): List<UserPlaylistEntryEntity>

    @Query("SELECT * FROM favorites")
    abstract suspend fun favorites(): List<UserFavoriteEntity>

    @Query("SELECT * FROM collection_favorites")
    abstract suspend fun collectionFavorites(): List<UserCollectionFavoriteEntity>

    @Query("SELECT * FROM play_events")
    abstract suspend fun playEvents(): List<UserPlayEventEntity>

    @Query("SELECT * FROM wear_selections")
    abstract suspend fun wearSelections(): List<UserWearSelectionEntity>

    @Query("SELECT * FROM episode_downloads")
    abstract suspend fun episodeDownloads(): List<UserEpisodeDownloadEntity>

    @Query("SELECT * FROM episode_stars")
    abstract suspend fun episodeStars(): List<UserEpisodeStarEntity>

    @Query("SELECT * FROM ard_books")
    abstract suspend fun ardBooks(): List<UserArdBookEntity>

    @Upsert
    protected abstract suspend fun upsertServers(rows: List<UserServerEntity>)

    @Delete
    protected abstract suspend fun deleteServers(rows: List<UserServerEntity>)

    @Upsert
    protected abstract suspend fun upsertSpokenServers(rows: List<UserSpokenServerEntity>)

    @Delete
    protected abstract suspend fun deleteSpokenServers(rows: List<UserSpokenServerEntity>)

    @Upsert
    protected abstract suspend fun upsertPodcasts(rows: List<UserPodcastEntity>)

    @Delete
    protected abstract suspend fun deletePodcasts(rows: List<UserPodcastEntity>)

    @Upsert
    protected abstract suspend fun upsertListeningStats(rows: List<UserListeningStatEntity>)

    @Delete
    protected abstract suspend fun deleteListeningStats(rows: List<UserListeningStatEntity>)

    @Upsert
    protected abstract suspend fun upsertListeningDays(rows: List<UserListeningDayEntity>)

    @Delete
    protected abstract suspend fun deleteListeningDays(rows: List<UserListeningDayEntity>)

    @Upsert
    protected abstract suspend fun upsertProgress(rows: List<UserBookProgressEntity>)

    @Delete
    protected abstract suspend fun deleteProgress(rows: List<UserBookProgressEntity>)

    /** Backed-up progress under IDs that no longer exist after switching the source (concept 12.10). */
    @Query("DELETE FROM book_progress WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    abstract suspend fun deleteProgressKeys(serverId: String, remoteIds: List<String>)

    @Upsert
    protected abstract suspend fun upsertBookmarks(rows: List<UserBookmarkEntity>)

    @Delete
    protected abstract suspend fun deleteBookmarks(rows: List<UserBookmarkEntity>)

    /** Like [deleteProgressKeys] for the bookmarks. */
    @Query("DELETE FROM bookmarks WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    abstract suspend fun deleteBookmarkKeys(serverId: String, remoteIds: List<String>)

    @Upsert
    protected abstract suspend fun upsertPlaylists(rows: List<UserPlaylistEntity>)

    @Delete
    protected abstract suspend fun deletePlaylists(rows: List<UserPlaylistEntity>)

    @Upsert
    protected abstract suspend fun upsertPlaylistEntries(rows: List<UserPlaylistEntryEntity>)

    @Delete
    protected abstract suspend fun deletePlaylistEntries(rows: List<UserPlaylistEntryEntity>)

    @Upsert
    protected abstract suspend fun upsertFavorites(rows: List<UserFavoriteEntity>)

    @Delete
    protected abstract suspend fun deleteFavorites(rows: List<UserFavoriteEntity>)

    @Upsert
    protected abstract suspend fun upsertCollectionFavorites(rows: List<UserCollectionFavoriteEntity>)

    @Delete
    protected abstract suspend fun deleteCollectionFavorites(rows: List<UserCollectionFavoriteEntity>)

    @Upsert
    protected abstract suspend fun upsertPlayEvents(rows: List<UserPlayEventEntity>)

    @Delete
    protected abstract suspend fun deletePlayEvents(rows: List<UserPlayEventEntity>)

    @Upsert
    protected abstract suspend fun upsertWearSelections(rows: List<UserWearSelectionEntity>)

    @Delete
    protected abstract suspend fun deleteWearSelections(rows: List<UserWearSelectionEntity>)

    @Upsert
    protected abstract suspend fun upsertEpisodeDownloads(rows: List<UserEpisodeDownloadEntity>)

    @Delete
    protected abstract suspend fun deleteEpisodeDownloads(rows: List<UserEpisodeDownloadEntity>)

    @Upsert
    protected abstract suspend fun upsertEpisodeStars(rows: List<UserEpisodeStarEntity>)

    @Delete
    protected abstract suspend fun deleteEpisodeStars(rows: List<UserEpisodeStarEntity>)

    @Upsert
    protected abstract suspend fun upsertArdBooks(rows: List<UserArdBookEntity>)

    @Delete
    protected abstract suspend fun deleteArdBooks(rows: List<UserArdBookEntity>)

    /** Like [deleteProgressKeys] for the starred episodes. */
    @Query("DELETE FROM episode_stars WHERE serverId = :serverId AND remoteId IN (:remoteIds)")
    abstract suspend fun deleteEpisodeStarKeys(serverId: String, remoteIds: List<String>)

    @Query("DELETE FROM book_progress WHERE serverId IN (:serverIds)")
    protected abstract suspend fun deleteProgressOf(serverIds: List<String>)

    @Query("DELETE FROM bookmarks WHERE serverId IN (:serverIds)")
    protected abstract suspend fun deleteBookmarksOf(serverIds: List<String>)

    @Query("DELETE FROM episode_downloads WHERE serverId IN (:serverIds)")
    protected abstract suspend fun deleteEpisodeDownloadsOf(serverIds: List<String>)

    @Query("DELETE FROM episode_stars WHERE serverId IN (:serverIds)")
    protected abstract suspend fun deleteEpisodeStarsOf(serverIds: List<String>)

    /**
     * Writes all changes in one go; [UserDataChanges.isEmpty] saves the call. With a subscription or
     * Audiobookshelf server, its progress and bookmarks are dropped too, including those not yet applied; with
     * a subscription also its downloads and starred episodes.
     */
    @Transaction
    open suspend fun apply(changes: UserDataChanges) {
        with(changes) {
            if (servers.upserts.isNotEmpty()) upsertServers(servers.upserts)
            if (servers.deletes.isNotEmpty()) deleteServers(servers.deletes)
            if (spokenServers.upserts.isNotEmpty()) upsertSpokenServers(spokenServers.upserts)
            if (spokenServers.deletes.isNotEmpty()) deleteSpokenServers(spokenServers.deletes)
            if (podcasts.upserts.isNotEmpty()) upsertPodcasts(podcasts.upserts)
            if (podcasts.deletes.isNotEmpty()) deletePodcasts(podcasts.deletes)
            if (listeningStats.upserts.isNotEmpty()) upsertListeningStats(listeningStats.upserts)
            if (listeningStats.deletes.isNotEmpty()) deleteListeningStats(listeningStats.deletes)
            if (listeningDays.upserts.isNotEmpty()) upsertListeningDays(listeningDays.upserts)
            if (listeningDays.deletes.isNotEmpty()) deleteListeningDays(listeningDays.deletes)
            if (progress.upserts.isNotEmpty()) upsertProgress(progress.upserts)
            if (progress.deletes.isNotEmpty()) deleteProgress(progress.deletes)
            if (bookmarks.upserts.isNotEmpty()) upsertBookmarks(bookmarks.upserts)
            if (bookmarks.deletes.isNotEmpty()) deleteBookmarks(bookmarks.deletes)
            if (playlists.upserts.isNotEmpty()) upsertPlaylists(playlists.upserts)
            if (playlists.deletes.isNotEmpty()) deletePlaylists(playlists.deletes)
            if (playlistEntries.upserts.isNotEmpty()) upsertPlaylistEntries(playlistEntries.upserts)
            if (playlistEntries.deletes.isNotEmpty()) deletePlaylistEntries(playlistEntries.deletes)
            if (favorites.upserts.isNotEmpty()) upsertFavorites(favorites.upserts)
            if (favorites.deletes.isNotEmpty()) deleteFavorites(favorites.deletes)
            if (collectionFavorites.upserts.isNotEmpty()) upsertCollectionFavorites(collectionFavorites.upserts)
            if (collectionFavorites.deletes.isNotEmpty()) deleteCollectionFavorites(collectionFavorites.deletes)
            if (playEvents.upserts.isNotEmpty()) upsertPlayEvents(playEvents.upserts)
            if (playEvents.deletes.isNotEmpty()) deletePlayEvents(playEvents.deletes)
            if (wearSelections.upserts.isNotEmpty()) upsertWearSelections(wearSelections.upserts)
            if (wearSelections.deletes.isNotEmpty()) deleteWearSelections(wearSelections.deletes)
            if (episodeDownloads.upserts.isNotEmpty()) upsertEpisodeDownloads(episodeDownloads.upserts)
            if (episodeDownloads.deletes.isNotEmpty()) deleteEpisodeDownloads(episodeDownloads.deletes)
            if (episodeStars.upserts.isNotEmpty()) upsertEpisodeStars(episodeStars.upserts)
            if (episodeStars.deletes.isNotEmpty()) deleteEpisodeStars(episodeStars.deletes)
            if (ardBooks.upserts.isNotEmpty()) upsertArdBooks(ardBooks.upserts)
            if (ardBooks.deletes.isNotEmpty()) deleteArdBooks(ardBooks.deletes)
            val removedServers = podcasts.deletes.map { it.feedId } + spokenServers.deletes.map { it.serverId }
            removedServers.chunked(MAX_ARGS).forEach {
                deleteProgressOf(it)
                deleteBookmarksOf(it)
            }
            podcasts.deletes.map { it.feedId }.chunked(MAX_ARGS).forEach {
                deleteEpisodeDownloadsOf(it)
                deleteEpisodeStarsOf(it)
            }
        }
    }

    private companion object {
        /** Below SQLite's limit for parameters of a query. */
        const val MAX_ARGS = 500
    }
}

/** Rows of a table to write and to delete. */
data class RowChanges<T>(val upserts: List<T> = emptyList(), val deletes: List<T> = emptyList()) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()
}

/** Changes to all tables of `user.db`. */
data class UserDataChanges(
    val servers: RowChanges<UserServerEntity> = RowChanges(),
    val spokenServers: RowChanges<UserSpokenServerEntity> = RowChanges(),
    val podcasts: RowChanges<UserPodcastEntity> = RowChanges(),
    val listeningStats: RowChanges<UserListeningStatEntity> = RowChanges(),
    val listeningDays: RowChanges<UserListeningDayEntity> = RowChanges(),
    val progress: RowChanges<UserBookProgressEntity> = RowChanges(),
    val bookmarks: RowChanges<UserBookmarkEntity> = RowChanges(),
    val playlists: RowChanges<UserPlaylistEntity> = RowChanges(),
    val playlistEntries: RowChanges<UserPlaylistEntryEntity> = RowChanges(),
    val favorites: RowChanges<UserFavoriteEntity> = RowChanges(),
    val collectionFavorites: RowChanges<UserCollectionFavoriteEntity> = RowChanges(),
    val playEvents: RowChanges<UserPlayEventEntity> = RowChanges(),
    val wearSelections: RowChanges<UserWearSelectionEntity> = RowChanges(),
    val episodeDownloads: RowChanges<UserEpisodeDownloadEntity> = RowChanges(),
    val episodeStars: RowChanges<UserEpisodeStarEntity> = RowChanges(),
    val ardBooks: RowChanges<UserArdBookEntity> = RowChanges(),
) {
    val isEmpty: Boolean
        get() = servers.isEmpty && spokenServers.isEmpty && podcasts.isEmpty && listeningStats.isEmpty && listeningDays.isEmpty &&
            progress.isEmpty && bookmarks.isEmpty && playlists.isEmpty && playlistEntries.isEmpty && favorites.isEmpty && collectionFavorites.isEmpty &&
            playEvents.isEmpty &&
            wearSelections.isEmpty && episodeDownloads.isEmpty && episodeStars.isEmpty && ardBooks.isEmpty
}
