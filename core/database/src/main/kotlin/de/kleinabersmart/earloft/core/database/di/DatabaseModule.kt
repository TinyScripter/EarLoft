package de.kleinabersmart.earloft.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.dao.AbsSessionDao
import de.kleinabersmart.earloft.core.database.dao.BackupDao
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.LyricsDao
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.PlaybackDao
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EarLoftDatabase =
        Room.databaseBuilder(context, EarLoftDatabase::class.java, "earloft.db")
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER).createNewFile()
                    }
                },
            )
            .build()

    @Provides
    fun provideSourceDao(database: EarLoftDatabase): SourceDao = database.sourceDao()

    @Provides
    fun provideLibraryDao(database: EarLoftDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideMergeDao(database: EarLoftDatabase): MergeDao = database.mergeDao()

    @Provides
    fun providePlaybackDao(database: EarLoftDatabase): PlaybackDao = database.playbackDao()

    @Provides
    fun provideDownloadDao(database: EarLoftDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideSyncDao(database: EarLoftDatabase): SyncDao = database.syncDao()

    @Provides
    fun providePlaylistDao(database: EarLoftDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideLyricsDao(database: EarLoftDatabase): LyricsDao = database.lyricsDao()

    @Provides
    fun provideWearDao(database: EarLoftDatabase): WearDao = database.wearDao()

    @Provides
    fun provideBookDao(database: EarLoftDatabase): BookDao = database.bookDao()

    @Provides
    fun providePodcastDao(database: EarLoftDatabase): PodcastDao = database.podcastDao()

    @Provides
    fun provideStatsDao(database: EarLoftDatabase): StatsDao = database.statsDao()

    @Provides
    fun provideAbsSessionDao(database: EarLoftDatabase): AbsSessionDao = database.absSessionDao()

    @Provides
    fun provideBackupDao(database: EarLoftDatabase): BackupDao = database.backupDao()

    /** Nur das Handy nutzt sie; auf der Uhr wird sie nie angefordert und daher nie angelegt. */
    @Provides
    @Singleton
    fun provideUserDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME).build()

    @Provides
    fun provideUserDataDao(database: UserDatabase): UserDataDao = database.userDataDao()
}
