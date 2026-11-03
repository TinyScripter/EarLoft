package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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

    /** Schreibt alle Änderungen in einem Zug; [UserDataChanges.isEmpty] spart den Aufruf. */
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
        }
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
) {
    val isEmpty: Boolean
        get() = servers.isEmpty && spokenServers.isEmpty && podcasts.isEmpty && listeningStats.isEmpty && listeningDays.isEmpty
}
