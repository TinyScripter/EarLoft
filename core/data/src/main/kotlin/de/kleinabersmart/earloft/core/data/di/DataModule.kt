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

/** Scope für Arbeit, die unabhängig von einzelnen Bildschirmen weiterlaufen soll. */
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
 * Für langlebige Scopes (App, Service): Eine unbehandelte Ausnahme in einer Hintergrund-Coroutine wird geloggt, statt
 * den Prozess samt laufender Wiedergabe zu beenden (z. B. ein Fremdschlüssel, weil der Inhalt gerade gelöscht wurde).
 */
val LoggingExceptionHandler = CoroutineExceptionHandler { _, e -> Log.e("ApplicationScope", "Unbehandelte Ausnahme", e) }
