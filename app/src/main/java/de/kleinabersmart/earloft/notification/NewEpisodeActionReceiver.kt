package de.kleinabersmart.earloft.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aktionen „Als Nächstes“ und „Laden“ der Benachrichtigung über genau eine neue Folge (`podcast_improvements.md` S13);
 * beide ohne die App zu öffnen. „Abspielen“ öffnet die App ([AppNewContentNotifier.ACTION_PLAY_EPISODE]), damit die
 * Rückfrage zu mobilen Daten erscheinen kann.
 */
@AndroidEntryPoint
class NewEpisodeActionReceiver : BroadcastReceiver() {

    @Inject lateinit var continueListening: ContinueListening

    @Inject lateinit var downloads: EpisodeDownloads

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1).takeIf { it >= 0 } ?: return
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_PLAY_NEXT -> continueListening.playNext(bookId)
                    ACTION_DOWNLOAD -> downloads.download(bookId)
                }
            } finally {
                NotificationManagerCompat.from(context).cancel(AppNewContentNotifier.notificationId(SpokenMode.PODCASTS))
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_NEXT = "de.kleinabersmart.earloft.NEW_EPISODE_PLAY_NEXT"
        const val ACTION_DOWNLOAD = "de.kleinabersmart.earloft.NEW_EPISODE_DOWNLOAD"
        const val EXTRA_BOOK_ID = "de.kleinabersmart.earloft.BOOK_ID"
    }
}
