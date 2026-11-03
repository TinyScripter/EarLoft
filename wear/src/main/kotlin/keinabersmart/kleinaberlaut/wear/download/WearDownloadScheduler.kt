package keinabersmart.kleinaberlaut.wear.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.download.DownloadScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startet den [WearDownloadWorker]. Anders als am Handy gibt es keine Netz-Bedingung: Der Worker fordert
 * WLAN selbst an, weil der Bluetooth-Proxy des Handys für WorkManager ebenfalls als Netz gilt (Wear-Konzept 8.2).
 * Nie bei niedrigem Akku; nach Einstellung oder nach mehreren Versuchen ohne WLAN ([enqueueOnCharger]) nur am Ladegerät.
 */
@Singleton
class WearDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val state: WatchState,
) : DownloadScheduler {

    override suspend fun enqueue(replace: Boolean) {
        enqueue(requireCharging = state.settings.value.requireCharging, policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /**
     * Aus dem laufenden Worker, wenn es wiederholt kein WLAN gab: erst am Ladegerät weiter (dort ist die Uhr meist im WLAN),
     * statt alle paar Minuten das WLAN einzuschalten. Wird angehängt, läuft also nach dem jetzigen Auftrag; eine neue
     * Auswahl ersetzt ihn wieder durch einen normalen.
     */
    fun enqueueOnCharger() {
        enqueue(requireCharging = true, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(requireCharging: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<WearDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresCharging(requireCharging)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "wear-downloads"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WearDownloadModule {
    @Binds
    fun bindScheduler(scheduler: WearDownloadScheduler): DownloadScheduler
}
