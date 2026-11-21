package de.kleinabersmart.earloft.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import okhttp3.OkHttpClient

/**
 * Debug builds only: the app's own singletons for the instrumented token expiry test (`AbsTokenExpiryTest`). Entry points
 * declared in the test APK don't reach the app's component, hence here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AbsTokenTestEntryPoint {
    fun tokenTestServers(): AudiobookshelfServers

    fun tokenTestRegistry(): AbsServerRegistry

    fun tokenTestApi(): AbsApi

    fun tokenTestClient(): OkHttpClient
}
