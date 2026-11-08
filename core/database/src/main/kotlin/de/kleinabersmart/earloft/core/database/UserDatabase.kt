package de.kleinabersmart.earloft.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.UserListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.UserPodcastEntity
import de.kleinabersmart.earloft.core.database.entity.UserServerEntity
import de.kleinabersmart.earloft.core.database.entity.UserSpokenServerEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity

/**
 * Die gesicherten Nutzerdaten (`user.db`, Review K9). Die große [EarLoftDatabase] ist vom Backup ausgeschlossen und
 * wird aus den Quellen neu aufgebaut; was keine Quelle kennt, steht zusätzlich hier und kommt so beim Gerätewechsel mit.
 * Die Hauptdatenbank bleibt die Arbeitskopie: Änderungen werden hierher gespiegelt und nach einem Neuaufbau zurückgespielt.
 *
 * Versionen: 1 Server, Abos, Statistik; 2 Fortschritt; 3 lokale Playlists, Favoriten, Hörverlauf, Auswahl für die Uhr;
 * 5 archivierte Abos; 6 ohne Cast-Adresse je Server.
 */
@Database(
    entities = [
        UserServerEntity::class,
        UserSpokenServerEntity::class,
        UserPodcastEntity::class,
        UserListeningStatEntity::class,
        UserListeningDayEntity::class,
        UserBookProgressEntity::class,
        UserPlaylistEntity::class,
        UserPlaylistEntryEntity::class,
        UserFavoriteEntity::class,
        UserPlayEventEntity::class,
        UserWearSelectionEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = UserDatabase.DropCastBaseUrl::class),
    ],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDataDao(): UserDataDao

    @DeleteColumn(tableName = "servers", columnName = "castBaseUrl")
    class DropCastBaseUrl : AutoMigrationSpec

    companion object {
        /** Dateiname; die Backup-Regeln der App schließen nur die Hauptdatenbank aus, diese Datei nicht. */
        const val NAME = "user.db"
    }
}
