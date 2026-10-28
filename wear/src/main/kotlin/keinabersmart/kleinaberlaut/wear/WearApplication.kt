package keinabersmart.kleinaberlaut.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.wear.sync.PhoneSyncWorker
import keinabersmart.kleinaberlaut.wear.sync.WatchInfoPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Die Uhr startet bewusst keinen `LibraryRefresher`, keine Sync-Queue und keine `AutoDownloads`
 * (Wear-Konzept 7.3): Was auf die Uhr kommt, bestimmt allein das Handy.
 */
@HiltAndroidApp
class WearApplication : Application() {

    @Inject lateinit var watchInfoPublisher: WatchInfoPublisher
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { watchInfoPublisher.publish() }
        // Holt nach, was ankam, während die App nicht lief bzw. der Listener nichts gemeldet hat.
        PhoneSyncWorker.enqueue(this)
    }
}
