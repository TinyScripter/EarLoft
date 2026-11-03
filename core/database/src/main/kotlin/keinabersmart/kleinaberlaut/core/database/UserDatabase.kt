package keinabersmart.kleinaberlaut.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import keinabersmart.kleinaberlaut.core.database.dao.UserDataDao
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningDayEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningStatEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPodcastEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserSpokenServerEntity

/**
 * Die gesicherten Nutzerdaten (`user.db`, Review K9). Die große [KleinaberlautDatabase] ist vom Backup ausgeschlossen und
 * wird aus den Quellen neu aufgebaut; was keine Quelle kennt, steht zusätzlich hier und kommt so beim Gerätewechsel mit.
 * Die Hauptdatenbank bleibt die Arbeitskopie: Änderungen werden hierher gespiegelt und nach einem Neuaufbau zurückgespielt.
 */
@Database(
    entities = [
        UserServerEntity::class,
        UserSpokenServerEntity::class,
        UserPodcastEntity::class,
        UserListeningStatEntity::class,
        UserListeningDayEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDataDao(): UserDataDao

    companion object {
        /** Dateiname; die Backup-Regeln der App schließen nur die Hauptdatenbank aus, diese Datei nicht. */
        const val NAME = "user.db"
    }
}
