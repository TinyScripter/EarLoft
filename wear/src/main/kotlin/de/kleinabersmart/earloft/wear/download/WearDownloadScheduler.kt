package de.kleinabersmart.earloft.wear.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startet den [WearDownloadWorker]. Anders als am Handy gibt es keine Netz-Bedingung: Der Worker fordert
 * WLAN selbst an, weil der Bluetooth-Proxy des Handys für WorkManager ebenfalls als Netz gilt (Wear-Konzept 8.2).
 * Nie bei niedrigem Akku; nach Einstellung oder nach mehreren Versuchen ohne WLAN ([enqueueOnCharger]) nur am Ladegerät.
 * Wiederholungen plant der Worker selbst ([enqueueRetry]) statt über den Backoff von WorkManager (Review W1).
 */
@Singleton
class WearDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val state: WatchState,
) : DownloadScheduler {

    override suspend fun enqueue(replace: Boolean) {
        // Ein ersetzter Auftrag beginnt von vorn, auch mit den Versuchen ohne WLAN (wie früher ein frischer runAttemptCount).
        if (replace) state.clearRetries()
        enqueue(
            requireCharging = state.settings.value.requireCharging,
            policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    /**
     * Bei jedem Prozessstart und Abgleich: Ein wartender Auftrag bleibt – auch einer, der nur noch am Ladegerät läuft oder auf
     * seine Wiederholung wartet. Angehängt begänne sonst jedes Mal ein neuer mit fünf frischen WLAN-Versuchen.
     */
    override suspend fun resume() {
        enqueue(requireCharging = state.settings.value.requireCharging, policy = ExistingWorkPolicy.KEEP)
    }

    /**
     * Aus dem laufenden Worker, wenn es wiederholt kein WLAN gab bzw. das Handy fehlte: erst am Ladegerät weiter (dort ist die
     * Uhr meist im WLAN), statt alle paar Minuten das WLAN einzuschalten. Wird angehängt, läuft also nach dem jetzigen Auftrag;
     * eine neue Auswahl ersetzt ihn wieder durch einen normalen.
     */
    fun enqueueOnCharger() {
        state.clearRetries()
        enqueue(requireCharging = true, policy = ExistingWorkPolicy.APPEND_OR_REPLACE, onCharger = true)
    }

    /**
     * Aus dem laufenden Worker: derselbe Auftrag noch einmal nach [delayMs], angehängt an den jetzigen (der danach erfolgreich
     * endet). Ein neuer Auftrag statt `Result.retry()`: Stoppt das System ihn (etwa beim kurzen Trennen des adaptiven Ladens),
     * plant WorkManager ihn ohne wachsenden Backoff neu (Review W1). [onCharger] behält die Lade-Bedingung des jetzigen.
     */
    fun enqueueRetry(delayMs: Long, onCharger: Boolean) {
        enqueue(
            requireCharging = onCharger || state.settings.value.requireCharging,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            onCharger = onCharger,
            delayMs = delayMs,
        )
    }

    private fun enqueue(requireCharging: Boolean, policy: ExistingWorkPolicy, onCharger: Boolean = false, delayMs: Long = 0) {
        val request = OneTimeWorkRequestBuilder<WearDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresCharging(requireCharging)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(WearDownloadWorker.KEY_ON_CHARGER to onCharger))
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
