package de.kleinabersmart.earloft.wear

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.model.SpokenEffects
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
 * The watch deliberately starts no `LibraryRefresher`, no sync queue and no `AutoDownloads`
 * (Wear concept 7.3): what goes onto the watch is determined by the phone alone.
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
        // Before the playback service starts: only downloads, not through the speaker (Wear concept 7.3, 9).
        playbackPolicy.allowStreaming = false
        playbackPolicy.requireSuitableOutput = !debugAllowsSpeaker()
        // Books: progress and listening time go to Audiobookshelf via the phone (Wear concept 16.2).
        playbackPolicy.syncsWithBookServers = false
        playbackPolicy.shakeToExtendSleepTimer = false
        // ReplayGain as on the phone (W2): the settings come with /earloft/settings.
        applicationScope.launch {
            watchState.settings.map { it.replayGainMode to it.preAmpDb }.distinctUntilChanged().collect { (mode, preAmp) ->
                replayGainSettings.setMode(mode)
                replayGainSettings.setPreAmpDb(preAmp)
            }
        }
        // Skip intervals as on the phone; playback service and player read them from SpokenSettings.
        applicationScope.launch {
            watchState.settings.map {
                SeekSteps(it.bookSeekBackSeconds, it.bookSeekForwardSeconds) to
                    SeekSteps(it.podcastSeekBackSeconds, it.podcastSeekForwardSeconds)
            }.distinctUntilChanged().collect { (book, podcast) ->
                spokenSettings.setBookSeek(book)
                spokenSettings.setPodcastSeek(podcast)
            }
        }
        // Defaults for speed and effects as on the phone (concept 12.4); subscriptions and episodes bring their own.
        applicationScope.launch {
            watchState.settings.map {
                SpokenEffects(it.bookSpeed, it.bookSkipSilence, it.bookVolumeBoostDb) to
                    SpokenEffects(it.podcastSpeed, it.podcastSkipSilence, it.podcastVolumeBoostDb)
            }.distinctUntilChanged().collect { (book, podcast) ->
                spokenSettings.setBookEffects(book)
                spokenSettings.setPodcastEffects(podcast)
            }
        }
        applicationScope.launch { watchInfoPublisher.publish() }
        watchStatusPublisher.start(applicationScope)
        // Episodes listened to on the watch go to the phone (Wear concept 16.1).
        episodeReporter.start(applicationScope)
        // Catches up on whatever arrived while the app was not running or the listener did not report anything.
        PhoneSyncWorker.enqueue(this)
    }

    /**
     * Only for tests in the emulator, which has no Bluetooth output: in debuggable builds the switch
     * `debug_allow_speaker` in `watch_state` allows the speaker (see `stand.md`, Wear test notes).
     */
    private fun debugAllowsSpeaker(): Boolean {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return debuggable && getSharedPreferences("watch_state", MODE_PRIVATE).getBoolean("debug_allow_speaker", false)
    }
}
