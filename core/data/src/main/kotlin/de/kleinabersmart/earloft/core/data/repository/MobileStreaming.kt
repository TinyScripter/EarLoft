package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Whether streaming is currently over a metered network (mobile data) and at which quality (concept 7.4). */
@Singleton
open class MobileStreaming @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val librarySettings: LibrarySettings,
) {
    /** Metered or no network. `false` on Wi-Fi. */
    open fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** Format and maximum bitrate (kbit/s) the server should currently transcode streams to; `null` for original. */
    open suspend fun transcodeTo(): Pair<String, Int>? =
        if (isMetered()) librarySettings.mobileStreamQuality.first().transcodeTo else null
}
