package de.kleinabersmart.earloft.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.entity.UserArdBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.UserCollectionFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeDownloadEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeStarEntity
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
import java.io.File

/**
 * Die gesicherten Nutzerdaten (`user.db`, Review K9). Die große [EarLoftDatabase] ist vom Backup ausgeschlossen und
 * wird aus den Quellen neu aufgebaut; was keine Quelle kennt, steht zusätzlich hier und kommt so beim Gerätewechsel mit.
 * Die Hauptdatenbank bleibt die Arbeitskopie: Änderungen werden hierher gespiegelt und nach einem Neuaufbau zurückgespielt.
 *
 * Versionen: 1 Server, Abos, Statistik; 2 Fortschritt; 3 lokale Playlists, Favoriten, Hörverlauf, Auswahl für die Uhr;
 * 5 archivierte Abos; 6 ohne Cast-Adresse je Server; 7 OpenID je Audiobookshelf-Server; 8 Downloads der Folgen; 9 Favoriten
 * von Alben und Künstlern; 10 „aus Weiterhören genommen“ am Fortschritt; 11 Tempo und Effekte je Abo und Buch, Tempo nullbar; 12 gesparte Zeit je Tag;
 * 13 Folgenliste und „Weiterhören“ je Abo; 14 Lesezeichen; 15 Bereithalten je Abo; 16 gemerkte Folgen; 17 abgewählte Buch-Bibliotheken;
 * 18 Bücher aus ARD Sounds.
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
        UserEpisodeDownloadEntity::class,
        UserCollectionFavoriteEntity::class,
        UserBookmarkEntity::class,
        UserEpisodeStarEntity::class,
        UserArdBookEntity::class,
    ],
    version = UserDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = UserDatabase.DropCastBaseUrl::class),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11, spec = UserDatabase.InheritSpeed::class),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
    ],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDataDao(): UserDataDao

    @DeleteColumn(tableName = "servers", columnName = "castBaseUrl")
    class DropCastBaseUrl : AutoMigrationSpec

    /** Wie [EarLoftDatabase.InheritSpeed]: 1,0 folgt jetzt der Vorgabe von Abo bzw. Bereich. */
    class InheritSpeed : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE book_progress SET speed = NULL WHERE speed = 1.0")
        }
    }

    companion object {
        /** Dateiname; die Backup-Regeln der App schließen nur die Hauptdatenbank aus, diese Datei nicht. */
        const val NAME = "user.db"

        /** Schemaversion; die Komplett-Sicherung lehnt Dateien einer neueren ab. */
        const val VERSION = 18

        /**
         * Eine Kopie außerhalb der App-Datenbanken (Komplett-Sicherung, `FullBackup`): ältere Fassungen werden beim Öffnen
         * migriert. Ohne WAL, damit nach [close] alles in [file] steht.
         */
        fun openFile(context: Context, file: File): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, file.absolutePath)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
