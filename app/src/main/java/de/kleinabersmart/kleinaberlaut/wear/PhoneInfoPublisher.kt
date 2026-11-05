package de.kleinabersmart.kleinaberlaut.wear

import android.content.Context
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearCodec
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearPaths
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearPhoneInfoDto
import de.kleinabersmart.kleinaberlaut.core.wearsync.putPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet der Uhr, dass die Handy-App installiert ist und welches Protokoll sie spricht
 * (Wear-Konzept 4, [WearPaths.PHONE_INFO]). Ohne Uhr bzw. Data Layer passiert nichts.
 */
@Singleton
class PhoneInfoPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataClient: DataClient,
) {
    suspend fun publish() {
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        dataClient.putPayload(WearPaths.PHONE_INFO, WearCodec.encode(WearPhoneInfoDto(appVersion = appVersion)))
    }
}
