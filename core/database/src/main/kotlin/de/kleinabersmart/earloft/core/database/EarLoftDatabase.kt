package de.kleinabersmart.earloft.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import de.kleinabersmart.earloft.core.database.dao.AbsSessionDao
import de.kleinabersmart.earloft.core.database.dao.BackupDao
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.LyricsDao
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.PlaybackDao
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.AbsSessionEntity
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.AlbumFtsEntity
import de.kleinabersmart.earloft.core.database.entity.AlbumSourceEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistFtsEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistSourceEntity
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.LyricsEntity
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.QueueItemEntity
import de.kleinabersmart.earloft.core.database.entity.ServerStatsCacheEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongFtsEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.database.entity.SyncActionEntity
import de.kleinabersmart.earloft.core.database.entity.WearBookEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import java.io.File

@Database(
    entities = [
        SourceEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        SongSourceEntity::class,
        SongArtistCrossRef::class,
        AlbumArtistCrossRef::class,
        DownloadEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        LyricsEntity::class,
        QueueItemEntity::class,
        PlayEventEntity::class,
        SyncActionEntity::class,
        SongFtsEntity::class,
        AlbumFtsEntity::class,
        ArtistFtsEntity::class,
        WearSelectionEntity::class,
        SpokenServerEntity::class,
        BookEntity::class,
        BookTrackEntity::class,
        BookChapterEntity::class,
        BookProgressEntity::class,
        PodcastFeedEntity::class,
        ListeningStatEntity::class,
        ListeningDayEntity::class,
        ServerStatsCacheEntity::class,
        AbsSessionEntity::class,
        WearBookEntity::class,
        AlbumSourceEntity::class,
        ArtistSourceEntity::class,
    ],
    version = 25,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = EarLoftDatabase.AddSearchIndex::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19, spec = EarLoftDatabase.DropCastBaseUrl::class),
        AutoMigration(from = 19, to = 20, spec = EarLoftDatabase.AddWebLinks::class),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25, spec = EarLoftDatabase.InheritSpeed::class),
    ],
)
abstract class EarLoftDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun libraryDao(): LibraryDao
    abstract fun mergeDao(): MergeDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun syncDao(): SyncDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun wearDao(): WearDao
    abstract fun bookDao(): BookDao
    abstract fun podcastDao(): PodcastDao
    abstract fun statsDao(): StatsDao
    abstract fun absSessionDao(): AbsSessionDao
    abstract fun backupDao(): BackupDao

    /** v1 → v2: FTS-Tabellen kommen hinzu und werden aus den vorhandenen Daten befüllt. */
    class AddSearchIndex : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO songs_fts(songs_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO albums_fts(albums_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO artists_fts(artists_fts) VALUES('rebuild')")
        }
    }

    /** v18 → v19: Die eigene Cast-Adresse je Server entfällt. */
    @DeleteColumn(tableName = "sources", columnName = "castBaseUrl")
    class DropCastBaseUrl : AutoMigrationSpec

    /**
     * v19 → v20: Webseiten von Podcasts und Folgen (Konzept 13). Ohne Validatoren lädt der nächste Abgleich jeden Feed
     * einmal ganz, statt mit 304 die Webseiten bis zur nächsten Änderung des Feeds auszulassen.
     */
    class AddWebLinks : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE podcast_feeds SET etag = NULL, lastModified = NULL")
        }
    }

    /**
     * v24 → v25: Tempo und Effekte je Abo und Bereich (Konzept 12.4). Das Tempo eines Buchs ist nur noch gesetzt, wenn es
     * ein eigenes ist; 1,0 war bisher der Standard für alle und folgt jetzt der Vorgabe.
     */
    class InheritSpeed : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE book_progress SET speed = NULL WHERE speed = 1.0")
        }
    }

    companion object {
        /**
         * Liegt in `noBackupFilesDir`, wenn diese Installation die Datenbank selbst angelegt hat (frisch installiert oder
         * Daten gelöscht). Gesicherte Dateien, die noch `bookId`s o. Ä. enthalten, stammen dann von einer anderen Datenbank.
         */
        const val CREATED_MARKER = "main_database_created"

        fun createdOnThisInstall(context: Context): Boolean = File(context.noBackupFilesDir, CREATED_MARKER).exists()
    }
}
