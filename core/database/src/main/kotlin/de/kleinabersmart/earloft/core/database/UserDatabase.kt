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
import de.kleinabersmart.earloft.core.database.entity.UserWearBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import java.io.File

/**
 * The backed-up user data (`user.db`, review K9). The large [EarLoftDatabase] is excluded from backup and
 * rebuilt from the sources; what no source knows is additionally stored here and thus comes along when switching devices.
 * The main database remains the working copy: changes are mirrored here and played back after a rebuild.
 *
 * Versions: 1 servers, subscriptions, statistics; 2 progress; 3 local playlists, favorites, listening history, selection for the watch;
 * 5 archived subscriptions; 6 without cast address per server; 7 OpenID per Audiobookshelf server; 8 episode downloads; 9 favorites
 * of albums and artists; 10 "removed from Continue listening" on progress; 11 speed and effects per subscription and book, speed nullable; 12 saved time per day;
 * 13 episode list and "Continue listening" per subscription; 14 bookmarks; 15 keep ready per subscription; 16 starred episodes; 17 deselected book libraries;
 * 18 books from ARD Sounds; 19 their parts; 20 album year in the keys of songs, albums and the watch selection; 21 "Keep up to date"
 * per subscription; 22 the watch's individually selected episodes and books.
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
        UserWearBookEntity::class,
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
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
    ],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userDataDao(): UserDataDao

    @DeleteColumn(tableName = "servers", columnName = "castBaseUrl")
    class DropCastBaseUrl : AutoMigrationSpec

    /** Like [EarLoftDatabase.InheritSpeed]: 1.0 now follows the default of the subscription or section. */
    class InheritSpeed : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE book_progress SET speed = NULL WHERE speed = 1.0")
        }
    }

    companion object {
        /** File name; the app's backup rules only exclude the main database, not this file. */
        const val NAME = "user.db"

        /** Schema version; the full backup rejects files of a newer one. */
        const val VERSION = 22

        /**
         * A copy outside the app databases (full backup, `FullBackup`): older versions are migrated when
         * opened. Without WAL, so that everything is in [file] after [close].
         */
        fun openFile(context: Context, file: File): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, file.absolutePath)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
