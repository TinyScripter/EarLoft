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
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.EpisodePersonEntity
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
import de.kleinabersmart.earloft.core.database.entity.SpokenAuthorEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenLibraryEntity
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
        SpokenLibraryEntity::class,
        SpokenAuthorEntity::class,
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
        BookmarkEntity::class,
        EpisodePersonEntity::class,
    ],
    version = 41,
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
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30, spec = EarLoftDatabase.ReloadChapterLists::class),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        AutoMigration(from = 34, to = 35, spec = EarLoftDatabase.DecodeTitles::class),
        AutoMigration(from = 35, to = 36, spec = EarLoftDatabase.ReadPodcasting20::class),
        AutoMigration(from = 36, to = 37),
        AutoMigration(from = 37, to = 38, spec = EarLoftDatabase.SeveralLibraries::class),
        AutoMigration(from = 38, to = 39),
        AutoMigration(from = 39, to = 40),
        AutoMigration(from = 40, to = 41),
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

    /**
     * v29 → v30: Kapitel bekommen Bild und Webseite. Gespeicherte Kapitellisten (JSON) hatten beides verworfen; ohne
     * vermerkte Quelle lädt `ensureChapters` sie beim nächsten Abspielen neu. Podlove-Kapitel kommen mit dem nächsten
     * vollständigen Abgleich, deshalb ohne Validatoren.
     */
    class ReloadChapterLists : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE books SET chapterSource = NULL WHERE chapterSource = 'JSON'")
            db.execSQL("UPDATE podcast_feeds SET etag = NULL, lastModified = NULL")
        }
    }

    /**
     * v34 → v35 (ohne Schemaänderung): Titel und Autoren aus Feeds werden jetzt von doppelt kodierten HTML-Entities befreit
     * („&amp;“ im Titel). Ohne Validatoren liest der nächste Abgleich jeden Feed einmal ganz und schreibt sie neu.
     */
    class DecodeTitles : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE podcast_feeds SET etag = NULL, lastModified = NULL")
        }
    }

    /**
     * v35 → v36: Staffel, Nummer, Art, Transkript und Mitwirkende der Folgen, Kennung und Unterstützen-Seite der Abos
     * (Podcasting 2.0, Konzept 12.5). Ohne Validatoren liest der nächste Abgleich jeden Feed einmal ganz.
     */
    class ReadPodcasting20 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE podcast_feeds SET etag = NULL, lastModified = NULL")
        }
    }

    /**
     * v37 → v38: mehrere Bibliotheken je Server (`books_improvements.md` M3). Die Bücher stammen bisher alle aus der einen
     * abgeglichenen Bibliothek des Servers.
     */
    class SeveralLibraries : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE books SET libraryId = (SELECT s.libraryId FROM spoken_servers s WHERE s.serverId = books.serverId) " +
                    "WHERE kind = 'BOOK'",
            )
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
