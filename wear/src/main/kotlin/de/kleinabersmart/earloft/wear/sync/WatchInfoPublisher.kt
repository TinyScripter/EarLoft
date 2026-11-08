package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearWatchInfoDto
import de.kleinabersmart.earloft.core.wearsync.putPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet dem Handy, dass die Uhr-App installiert ist ([WearPaths.WATCH_INFO]) – zusätzlich zur
 * Capability, die zwischen manchen Geräten nicht ankommt (Wear-Konzept 4).
 */
@Singleton
class WatchInfoPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
) {
    suspend fun publish() {
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        dataClient.putPayload(WearPaths.WATCH_INFO, WearCodec.encode(WearWatchInfoDto(appVersion = appVersion)))
    }
}
