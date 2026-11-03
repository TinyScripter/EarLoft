package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import keinabersmart.kleinaberlaut.core.database.entity.UserBookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserFavoriteEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserWearSelectionEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningDayEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningStatEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPodcastEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserSpokenServerEntity

/** Lesen und Ändern der gesicherten Nutzerdaten in `user.db`. */
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

    @Query("SELECT * FROM playlists")
    abstract suspend fun playlists(): List<UserPlaylistEntity>

    @Query("SELECT * FROM playlist_entries")
    abstract suspend fun playlistEntries(): List<UserPlaylistEntryEntity>

    @Query("SELECT * FROM favorites")
    abstract suspend fun favorites(): List<UserFavoriteEntity>

    @Query("SELECT * FROM play_events")
    abstract suspend fun playEvents(): List<UserPlayEventEntity>

    @Query("SELECT * FROM wear_selections")
    abstract suspend fun wearSelections(): List<UserWearSelectionEntity>

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
    protected abstract suspend fun upsertPlayEvents(rows: List<UserPlayEventEntity>)

    @Delete
    protected abstract suspend fun deletePlayEvents(rows: List<UserPlayEventEntity>)

    @Upsert
    protected abstract suspend fun upsertWearSelections(rows: List<UserWearSelectionEntity>)

    @Delete
    protected abstract suspend fun deleteWearSelections(rows: List<UserWearSelectionEntity>)

    @Query("DELETE FROM book_progress WHERE serverId IN (:serverIds)")
    protected abstract suspend fun deleteProgressOf(serverIds: List<String>)

    /**
     * Schreibt alle Änderungen in einem Zug; [UserDataChanges.isEmpty] spart den Aufruf. Mit einem Abo oder
     * Audiobookshelf-Server fällt auch sein Fortschritt weg, auch der noch nicht angewendete.
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
            if (playlists.upserts.isNotEmpty()) upsertPlaylists(playlists.upserts)
            if (playlists.deletes.isNotEmpty()) deletePlaylists(playlists.deletes)
            if (playlistEntries.upserts.isNotEmpty()) upsertPlaylistEntries(playlistEntries.upserts)
            if (playlistEntries.deletes.isNotEmpty()) deletePlaylistEntries(playlistEntries.deletes)
            if (favorites.upserts.isNotEmpty()) upsertFavorites(favorites.upserts)
            if (favorites.deletes.isNotEmpty()) deleteFavorites(favorites.deletes)
            if (playEvents.upserts.isNotEmpty()) upsertPlayEvents(playEvents.upserts)
            if (playEvents.deletes.isNotEmpty()) deletePlayEvents(playEvents.deletes)
            if (wearSelections.upserts.isNotEmpty()) upsertWearSelections(wearSelections.upserts)
            if (wearSelections.deletes.isNotEmpty()) deleteWearSelections(wearSelections.deletes)
            val removedServers = podcasts.deletes.map { it.feedId } + spokenServers.deletes.map { it.serverId }
            removedServers.chunked(MAX_ARGS).forEach { deleteProgressOf(it) }
        }
    }

    private companion object {
        /** Unter der Grenze von SQLite für Parameter einer Abfrage. */
        const val MAX_ARGS = 500
    }
}

/** Zu schreibende und zu löschende Zeilen einer Tabelle. */
data class RowChanges<T>(val upserts: List<T> = emptyList(), val deletes: List<T> = emptyList()) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()
}

/** Änderungen an allen Tabellen von `user.db`. */
data class UserDataChanges(
    val servers: RowChanges<UserServerEntity> = RowChanges(),
    val spokenServers: RowChanges<UserSpokenServerEntity> = RowChanges(),
    val podcasts: RowChanges<UserPodcastEntity> = RowChanges(),
    val listeningStats: RowChanges<UserListeningStatEntity> = RowChanges(),
    val listeningDays: RowChanges<UserListeningDayEntity> = RowChanges(),
    val progress: RowChanges<UserBookProgressEntity> = RowChanges(),
    val playlists: RowChanges<UserPlaylistEntity> = RowChanges(),
    val playlistEntries: RowChanges<UserPlaylistEntryEntity> = RowChanges(),
    val favorites: RowChanges<UserFavoriteEntity> = RowChanges(),
    val playEvents: RowChanges<UserPlayEventEntity> = RowChanges(),
    val wearSelections: RowChanges<UserWearSelectionEntity> = RowChanges(),
) {
    val isEmpty: Boolean
        get() = servers.isEmpty && spokenServers.isEmpty && podcasts.isEmpty && listeningStats.isEmpty && listeningDays.isEmpty &&
            progress.isEmpty && playlists.isEmpty && playlistEntries.isEmpty && favorites.isEmpty && playEvents.isEmpty && wearSelections.isEmpty
}
