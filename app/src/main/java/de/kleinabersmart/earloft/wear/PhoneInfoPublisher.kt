package de.kleinabersmart.earloft.wear

import android.content.Context
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPhoneInfoDto
import de.kleinabersmart.earloft.core.wearsync.putPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the watch that the phone app is installed and which protocol it speaks
 * (Wear concept 4, [WearPaths.PHONE_INFO]). Without a watch or Data Layer nothing happens.
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
