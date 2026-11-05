package de.kleinabersmart.tinyamp.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.UserDatabase
import de.kleinabersmart.tinyamp.core.database.dao.AbsSessionDao
import de.kleinabersmart.tinyamp.core.database.dao.BackupDao
import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import de.kleinabersmart.tinyamp.core.database.dao.PodcastDao
import de.kleinabersmart.tinyamp.core.database.dao.StatsDao
import de.kleinabersmart.tinyamp.core.database.dao.DownloadDao
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.dao.LyricsDao
import de.kleinabersmart.tinyamp.core.database.dao.MergeDao
import de.kleinabersmart.tinyamp.core.database.dao.PlaybackDao
import de.kleinabersmart.tinyamp.core.database.dao.PlaylistDao
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.dao.SyncDao
import de.kleinabersmart.tinyamp.core.database.dao.UserDataDao
import de.kleinabersmart.tinyamp.core.database.dao.WearDao
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TinyAmpDatabase =
        Room.databaseBuilder(context, TinyAmpDatabase::class.java, "tinyamp.db")
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        File(context.noBackupFilesDir, TinyAmpDatabase.CREATED_MARKER).createNewFile()
                    }
                },
            )
            .build()

    @Provides
    fun provideSourceDao(database: TinyAmpDatabase): SourceDao = database.sourceDao()

    @Provides
    fun provideLibraryDao(database: TinyAmpDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideMergeDao(database: TinyAmpDatabase): MergeDao = database.mergeDao()

    @Provides
    fun providePlaybackDao(database: TinyAmpDatabase): PlaybackDao = database.playbackDao()

    @Provides
    fun provideDownloadDao(database: TinyAmpDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideSyncDao(database: TinyAmpDatabase): SyncDao = database.syncDao()

    @Provides
    fun providePlaylistDao(database: TinyAmpDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideLyricsDao(database: TinyAmpDatabase): LyricsDao = database.lyricsDao()

    @Provides
    fun provideWearDao(database: TinyAmpDatabase): WearDao = database.wearDao()

    @Provides
    fun provideBookDao(database: TinyAmpDatabase): BookDao = database.bookDao()

    @Provides
    fun providePodcastDao(database: TinyAmpDatabase): PodcastDao = database.podcastDao()

    @Provides
    fun provideStatsDao(database: TinyAmpDatabase): StatsDao = database.statsDao()

    @Provides
    fun provideAbsSessionDao(database: TinyAmpDatabase): AbsSessionDao = database.absSessionDao()

    @Provides
    fun provideBackupDao(database: TinyAmpDatabase): BackupDao = database.backupDao()

    /** Nur das Handy nutzt sie; auf der Uhr wird sie nie angefordert und daher nie angelegt. */
    @Provides
    @Singleton
    fun provideUserDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME).build()

    @Provides
    fun provideUserDataDao(database: UserDatabase): UserDataDao = database.userDataDao()
}
