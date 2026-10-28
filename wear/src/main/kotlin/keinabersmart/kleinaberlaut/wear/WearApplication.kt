package keinabersmart.kleinaberlaut.wear

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackPolicy
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import keinabersmart.kleinaberlaut.wear.download.WatchState
import keinabersmart.kleinaberlaut.wear.sync.PhoneSyncWorker
import keinabersmart.kleinaberlaut.wear.sync.WatchInfoPublisher
import keinabersmart.kleinaberlaut.wear.sync.WatchStatusPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Die Uhr startet bewusst keinen `LibraryRefresher`, keine Sync-Queue und keine `AutoDownloads`
 * (Wear-Konzept 7.3): Was auf die Uhr kommt, bestimmt allein das Handy.
 */
@HiltAndroidApp
class WearApplication : Application() {

    @Inject lateinit var watchInfoPublisher: WatchInfoPublisher
    @Inject lateinit var watchStatusPublisher: WatchStatusPublisher
    @Inject lateinit var playbackPolicy: PlaybackPolicy
    @Inject lateinit var replayGainSettings: ReplayGainSettings
    @Inject lateinit var watchState: WatchState
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Vor dem Start des Wiedergabe-Service: nur Downloads, nicht über den Lautsprecher (Wear-Konzept 7.3, 9).
        playbackPolicy.allowStreaming = false
        playbackPolicy.requireSuitableOutput = !debugAllowsSpeaker()
        // ReplayGain wie am Handy (W2): Die Einstellungen kommen mit /kal/settings.
        applicationScope.launch {
            watchState.settings.map { it.replayGainMode to it.preAmpDb }.distinctUntilChanged().collect { (mode, preAmp) ->
                replayGainSettings.setMode(mode)
                replayGainSettings.setPreAmpDb(preAmp)
            }
        }
        applicationScope.launch { watchInfoPublisher.publish() }
        watchStatusPublisher.start(applicationScope)
        // Holt nach, was ankam, während die App nicht lief bzw. der Listener nichts gemeldet hat.
        PhoneSyncWorker.enqueue(this)
    }

    /**
     * Nur für Tests im Emulator, der keine Bluetooth-Ausgabe hat: In debuggable Builds erlaubt der Schalter
     * `debug_allow_speaker` in `watch_state` den Lautsprecher (siehe `stand.md`, Testhinweise Wear).
     */
    private fun debugAllowsSpeaker(): Boolean {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return debuggable && getSharedPreferences("watch_state", MODE_PRIVATE).getBoolean("debug_allow_speaker", false)
    }
}
