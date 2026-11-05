package de.kleinabersmart.tinyamp.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.refresh.NewContent
import de.kleinabersmart.tinyamp.core.data.refresh.NewContentNotifier
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR
import javax.inject.Inject
import javax.inject.Singleton

/** Meldet neue Folgen und Bücher als leise Benachrichtigung; ein Tipp öffnet die App. */
@Singleton
class AppNewContentNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NewContentNotifier {

    // areNotificationsEnabled() deckt auch die fehlende POST_NOTIFICATIONS-Berechtigung ab.
    @SuppressLint("MissingPermission")
    override fun show(content: NewContent) {
        if (content.isEmpty) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.new_content_channel_name))
                .build(),
        )
        val resources = context.resources
        val episodes = content.episodes.sumOf { it.count }
        val lines = buildList {
            if (episodes > 0) {
                val feeds = content.episodes.size
                add(
                    resources.getQuantityString(R.plurals.new_content_episodes, episodes, episodes) +
                        if (feeds > 1) " " + resources.getQuantityString(R.plurals.new_content_in_podcasts, feeds, feeds) else "",
                )
            }
            if (content.books > 0) add(resources.getQuantityString(R.plurals.new_content_books, content.books, content.books))
        }
        // Eine einzelne Quelle nennt sich beim Namen, sonst steht ein allgemeiner Titel da.
        val title = content.episodes.singleOrNull()?.takeIf { content.books == 0 }?.feedTitle
            ?: context.getString(R.string.new_content_title)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(DesignR.drawable.ic_book)
            .setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "new_content"
        const val NOTIFICATION_ID = 0x4e43
    }
}
