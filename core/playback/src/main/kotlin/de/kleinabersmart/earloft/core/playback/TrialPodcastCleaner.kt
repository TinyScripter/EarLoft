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
 * Räumt die Probeabos aus der Podcast-Vorschau auf (Konzept 12.10, Regeln in [planTrialCleanup]): Folgen ohne Bezug
 * verlieren Stelle und Download, Probeabos ohne Bezug und ohne offene Seite verschwinden. Läuft bei jeder Änderung der
 * Warteschlange, der Folgen, des geladenen Buchs und der offenen Seiten, und damit auch einmal beim Start der App.
 *
 * Offene Seiten kennt nur der Speicher ([TrialPodcastPages]). Startet der Prozess ohne Oberfläche neu (Job, Uhr, Widget),
 * hat sich die Seite, zu der der Nutzer gleich zurückkehrt, noch nicht angemeldet. Deshalb wird erst aufgeräumt, wenn die
 * App einmal sichtbar war und wiederhergestellte Seiten ihr ViewModel anlegen konnten.
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
                // Beim Abspielen oder Laden kommt die Folge erst einen Moment später in die Warteschlange.
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
                    Log.i(TAG, "Probeabos aufgeräumt: ${plan.episodes.size} Folgen, ${plan.feeds.size} Podcasts")
                }
        }
    }

    private companion object {
        const val TAG = "TrialPodcastCleaner"
        const val SETTLE_MS = 1_000L

        /** Zeit nach dem ersten Start der Oberfläche, in der wiederhergestellte Seiten sich anmelden. */
        const val UI_SETTLE_MS = 2_000L
    }
}
