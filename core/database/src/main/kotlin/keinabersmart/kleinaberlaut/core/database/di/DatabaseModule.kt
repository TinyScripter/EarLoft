package keinabersmart.kleinaberlaut.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.MergeDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.dao.SyncDao
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
}
