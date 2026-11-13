package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Ob gerade über ein getaktetes Netz (mobile Daten) gestreamt wird und in welcher Qualität (Konzept 7.4). */
@Singleton
open class MobileStreaming @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val librarySettings: LibrarySettings,
) {
    /** Getaktet oder ohne Netz. Im WLAN `false`. */
    open fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** Format und höchste Bitrate (kbit/s), in die der Server Streams gerade umwandeln soll; `null` für Original. */
    open suspend fun transcodeTo(): Pair<String, Int>? =
        if (isMetered()) librarySettings.mobileStreamQuality.first().transcodeTo else null
}
