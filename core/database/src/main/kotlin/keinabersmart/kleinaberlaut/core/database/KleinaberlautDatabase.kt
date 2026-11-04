package keinabersmart.kleinaberlaut.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import keinabersmart.kleinaberlaut.core.database.dao.AbsSessionDao
import keinabersmart.kleinaberlaut.core.database.dao.BackupDao
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.LyricsDao
import keinabersmart.kleinaberlaut.core.database.dao.MergeDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.dao.StatsDao
import keinabersmart.kleinaberlaut.core.database.dao.SyncDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.entity.AbsSessionEntity
import keinabersmart.kleinaberlaut.core.database.entity.AlbumArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.AlbumFtsEntity
import keinabersmart.kleinaberlaut.core.database.entity.ArtistEntity
import keinabersmart.kleinaberlaut.core.database.entity.ArtistFtsEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookChapterEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.database.entity.ListeningDayEntity
import keinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import keinabersmart.kleinaberlaut.core.database.entity.LyricsEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.database.entity.QueueItemEntity
import keinabersmart.kleinaberlaut.core.database.entity.ServerStatsCacheEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongFtsEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SpokenServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.SyncActionEntity
import keinabersmart.kleinaberlaut.core.database.entity.WearSelectionEntity
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
    ],
    version = 15,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = KleinaberlautDatabase.AddSearchIndex::class),
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
    ],
)
abstract class KleinaberlautDatabase : RoomDatabase() {
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

    companion object {
        /**
         * Liegt in `noBackupFilesDir`, wenn diese Installation die Datenbank selbst angelegt hat (frisch installiert oder
         * Daten gelöscht). Gesicherte Dateien, die noch `bookId`s o. Ä. enthalten, stammen dann von einer anderen Datenbank.
         */
        const val CREATED_MARKER = "main_database_created"

        fun createdOnThisInstall(context: Context): Boolean = File(context.noBackupFilesDir, CREATED_MARKER).exists()
    }
}
