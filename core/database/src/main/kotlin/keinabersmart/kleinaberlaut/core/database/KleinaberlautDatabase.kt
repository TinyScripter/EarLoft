package keinabersmart.kleinaberlaut.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.MergeDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.entity.AlbumArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.AlbumFtsEntity
import keinabersmart.kleinaberlaut.core.database.entity.ArtistEntity
import keinabersmart.kleinaberlaut.core.database.entity.ArtistFtsEntity
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.database.entity.LyricsEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntity
import keinabersmart.kleinaberlaut.core.database.entity.PlaylistEntryEntity
import keinabersmart.kleinaberlaut.core.database.entity.QueueItemEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongFtsEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SyncActionEntity

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
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = KleinaberlautDatabase.AddSearchIndex::class),
    ],
)
abstract class KleinaberlautDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun libraryDao(): LibraryDao
    abstract fun mergeDao(): MergeDao
    abstract fun playbackDao(): PlaybackDao

    /** v1 → v2: FTS-Tabellen kommen hinzu und werden aus den vorhandenen Daten befüllt. */
    class AddSearchIndex : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO songs_fts(songs_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO albums_fts(albums_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO artists_fts(artists_fts) VALUES('rebuild')")
        }
    }
}
