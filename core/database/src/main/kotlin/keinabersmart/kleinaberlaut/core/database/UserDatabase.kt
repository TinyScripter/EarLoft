package keinabersmart.kleinaberlaut.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import keinabersmart.kleinaberlaut.core.database.dao.UserDataDao
import keinabersmart.kleinaberlaut.core.database.entity.UserBookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserFavoriteEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningDayEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningStatEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPodcastEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserSpokenServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserWearSelectionEntity

/**
 * Die gesicherten Nutzerdaten (`user.db`, Review K9). Die große [KleinaberlautDatabase] ist vom Backup ausgeschlossen und
 * wird aus den Quellen neu aufgebaut; was keine Quelle kennt, steht zusätzlich hier und kommt so beim Gerätewechsel mit.
 * Die Hauptdatenbank bleibt die Arbeitskopie: Änderungen werden hierher gespiegelt und nach einem Neuaufbau zurückgespielt.
 *
 * Versionen: 1 Server, Abos, Statistik; 2 Fortschritt; 3 lokale Playlists, Favoriten, Hörverlauf, Auswahl für die Uhr.
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
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDataDao(): UserDataDao

    companion object {
        /** Dateiname; die Backup-Regeln der App schließen nur die Hauptdatenbank aus, diese Datei nicht. */
        const val NAME = "user.db"
    }
}
