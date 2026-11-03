package keinabersmart.kleinaberlaut.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.UserDatabase
import keinabersmart.kleinaberlaut.core.database.dao.AbsSessionDao
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.StatsDao
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.LyricsDao
import keinabersmart.kleinaberlaut.core.database.dao.MergeDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.dao.SyncDao
import keinabersmart.kleinaberlaut.core.database.dao.UserDataDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KleinaberlautDatabase =
        Room.databaseBuilder(context, KleinaberlautDatabase::class.java, "kleinaberlaut.db").build()

    @Provides
    fun provideSourceDao(database: KleinaberlautDatabase): SourceDao = database.sourceDao()

    @Provides
    fun provideLibraryDao(database: KleinaberlautDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun provideMergeDao(database: KleinaberlautDatabase): MergeDao = database.mergeDao()

    @Provides
    fun providePlaybackDao(database: KleinaberlautDatabase): PlaybackDao = database.playbackDao()

    @Provides
    fun provideDownloadDao(database: KleinaberlautDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideSyncDao(database: KleinaberlautDatabase): SyncDao = database.syncDao()

    @Provides
    fun providePlaylistDao(database: KleinaberlautDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideLyricsDao(database: KleinaberlautDatabase): LyricsDao = database.lyricsDao()

    @Provides
    fun provideWearDao(database: KleinaberlautDatabase): WearDao = database.wearDao()

    @Provides
    fun provideBookDao(database: KleinaberlautDatabase): BookDao = database.bookDao()

    @Provides
    fun providePodcastDao(database: KleinaberlautDatabase): PodcastDao = database.podcastDao()

    @Provides
    fun provideStatsDao(database: KleinaberlautDatabase): StatsDao = database.statsDao()

    @Provides
    fun provideAbsSessionDao(database: KleinaberlautDatabase): AbsSessionDao = database.absSessionDao()

    /** Nur das Handy nutzt sie; auf der Uhr wird sie nie angefordert und daher nie angelegt. */
    @Provides
    @Singleton
    fun provideUserDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME).build()

    @Provides
    fun provideUserDataDao(database: UserDatabase): UserDataDao = database.userDataDao()
}
