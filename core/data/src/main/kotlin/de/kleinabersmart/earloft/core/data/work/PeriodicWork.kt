package de.kleinabersmart.earloft.core.data.work

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Plant periodische Arbeit nur, wenn sie fehlt oder ihre Bedingungen bzw. ihr Takt nicht mehr stimmen (Review N3).
 *
 * `ExistingPeriodicWorkPolicy.UPDATE` ersetzt den JobScheduler-Auftrag auch bei gleichem Inhalt und erhöht jedes Mal die
 * Generation. Beim Prozessstart stornierte das genau den Auftrag, der den Prozess gerade gestartet hatte. `KEEP` allein
 * übernähme dagegen weder geänderte Einstellungen (Takt, Netz) noch neue Bedingungen nach einem App-Update. Deshalb wird
 * verglichen und nur bei einer Abweichung mit `UPDATE` ersetzt (der Takt läuft dabei weiter).
 *
 * Nicht verglichen werden Backoff und Flex-Zeit: Ändern die sich im Code, den Arbeitsnamen ändern.
 *
 * @return `true`, wenn neu eingeplant wurde.
 */
suspend fun WorkManager.ensurePeriodicWork(
    name: String,
    workerClass: Class<out ListenableWorker>,
    repeatInterval: Long,
    unit: TimeUnit,
    constraints: Constraints,
    configure: PeriodicWorkRequest.Builder.() -> Unit = {},
): Boolean {
    val intervalMillis = unit.toMillis(repeatInterval)
    if (isScheduled(getWorkInfosForUniqueWorkFlow(name).first(), constraints, intervalMillis)) return false
    val request = PeriodicWorkRequest.Builder(workerClass, repeatInterval, unit)
        .setConstraints(constraints)
        .apply(configure)
        .build()
    enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    Log.d(TAG, "$name eingeplant: alle $repeatInterval $unit, $constraints")
    return true
}

/** Steht unter dem Namen schon ein aktiver Auftrag mit genau diesen Bedingungen und diesem Takt? */
internal fun isScheduled(infos: List<WorkInfo>, constraints: Constraints, intervalMillis: Long): Boolean =
    infos.any { info ->
        !info.state.isFinished &&
            info.constraints == constraints &&
            info.periodicityInfo?.repeatIntervalMillis == intervalMillis
    }

private const val TAG = "PeriodicWork"
