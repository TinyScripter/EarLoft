package de.kleinabersmart.earloft.core.data.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope for work that should keep running independently of individual screens. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + LoggingExceptionHandler)
}

/**
 * For long-lived scopes (app, service): an unhandled exception in a background coroutine is logged instead of
 * killing the process along with ongoing playback (e.g. a foreign key, because the content was just deleted).
 */
val LoggingExceptionHandler = CoroutineExceptionHandler { _, e -> Log.e("ApplicationScope", "Unbehandelte Ausnahme", e) }
