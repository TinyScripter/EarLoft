package de.kleinabersmart.earloft.wear

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.playback.ReplayGainSettings
import de.kleinabersmart.earloft.wear.download.WatchState
import de.kleinabersmart.earloft.wear.sync.PhoneSyncWorker
import de.kleinabersmart.earloft.wear.sync.WatchEpisodeReporter
import de.kleinabersmart.earloft.wear.sync.WatchInfoPublisher
import de.kleinabersmart.earloft.wear.sync.WatchStatusPublisher
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
    @Inject lateinit var episodeReporter: WatchEpisodeReporter
    @Inject lateinit var playbackPolicy: PlaybackPolicy
    @Inject lateinit var replayGainSettings: ReplayGainSettings
    @Inject lateinit var watchState: WatchState
    @Inject lateinit var spokenSettings: SpokenSettings
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Vor dem Start des Wiedergabe-Service: nur Downloads, nicht über den Lautsprecher (Wear-Konzept 7.3, 9).
        playbackPolicy.allowStreaming = false
        playbackPolicy.requireSuitableOutput = !debugAllowsSpeaker()
        // Bücher: Stand und Hörzeit gehen über das Handy an Audiobookshelf (Wear-Konzept 16.2).
        playbackPolicy.syncsWithBookServers = false
        // ReplayGain wie am Handy (W2): Die Einstellungen kommen mit /earloft/settings.
        applicationScope.launch {
            watchState.settings.map { it.replayGainMode to it.preAmpDb }.distinctUntilChanged().collect { (mode, preAmp) ->
                replayGainSettings.setMode(mode)
                replayGainSettings.setPreAmpDb(preAmp)
            }
        }
        // Sprungweiten wie am Handy; Wiedergabe-Service und Player lesen sie aus SpokenSettings.
        applicationScope.launch {
            watchState.settings.map {
                SeekSteps(it.bookSeekBackSeconds, it.bookSeekForwardSeconds) to
                    SeekSteps(it.podcastSeekBackSeconds, it.podcastSeekForwardSeconds)
            }.distinctUntilChanged().collect { (book, podcast) ->
                spokenSettings.setBookSeek(book)
                spokenSettings.setPodcastSeek(podcast)
            }
        }
        applicationScope.launch { watchInfoPublisher.publish() }
        watchStatusPublisher.start(applicationScope)
        // Auf der Uhr gehörte Folgen ans Handy (Wear-Konzept 16.1).
        episodeReporter.start(applicationScope)
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
