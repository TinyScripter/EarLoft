package de.kleinabersmart.earloft.core.playback

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.TrialPodcastPages
import de.kleinabersmart.earloft.core.data.podcast.planTrialCleanup
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cleans up the trial subscriptions from the podcast preview (concept 12.10, rules in [planTrialCleanup]): episodes without a reference
 * lose their position and download, trial subscriptions without a reference and without an open page disappear. Runs on every change of the
 * queue, the episodes, the loaded book and the open pages, and thus also once at app start.
 *
 * Only memory knows the open pages ([TrialPodcastPages]). If the process restarts without UI (job, watch, widget),
 * the page the user is about to return to hasn't registered yet. That's why cleanup only happens once the
 * app has been visible and restored pages could create their ViewModel.
 */
@Singleton
class TrialPodcastCleaner @Inject constructor(
    private val podcasts: PodcastRepository,
    private val queue: EpisodeQueue,
    private val pages: TrialPodcastPages,
    private val downloads: EpisodeDownloads,
    private val queuePersistence: QueuePersistence,
) {
    private val started = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            delay(UI_SETTLE_MS)
            combine(
                combine(podcasts.trialFeedIds, podcasts.trialEpisodes, ::Pair),
                combine(queue.order, queue.hidden, ::Pair),
                queuePersistence.lastBookIds,
                pages.open.map { it.keys },
            ) { (feeds, episodes), (order, hidden), loaded, open ->
                planTrialCleanup(feeds, episodes, order, hidden, loaded, open)
            }
                .distinctUntilChanged()
                // When playing or downloading, the episode only enters the queue a moment later.
                .debounce(SETTLE_MS)
                .collect { plan ->
                    if (plan.isEmpty) return@collect
                    plan.episodes.forEach { bookId ->
                        downloads.remove(bookId)
                        podcasts.forgetProgress(bookId)
                    }
                    plan.feeds.forEach { feedId ->
                        downloads.removeFeed(feedId)
                        podcasts.unsubscribe(feedId)
                    }
                    Log.i(TAG, "Trial subscriptions cleaned up: ${plan.episodes.size} episodes, ${plan.feeds.size} podcasts")
                }
        }
    }

    private companion object {
        const val TAG = "TrialPodcastCleaner"
        const val SETTLE_MS = 1_000L

        /** Time after the first UI start during which restored pages register. */
        const val UI_SETTLE_MS = 2_000L
    }
}
