package de.kleinabersmart.earloft.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.MainActivity
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.withLaunchId
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.refresh.withUnread
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.model.bookAuthorLine
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.notification.NewContentTarget.Companion.putNewContentTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Announces new music, episodes and books as a silent notification, one per area (each with its own channel). A tap
 * opens Home in that area or, for exactly one album, podcast or book, its page ([NewContentTarget]); then the cover
 * is shown in the notification too. While the notification of an area is still there (neither tapped nor swiped away), the
 * next one takes over what it said ([withUnread], review5 P19).
 */
@Singleton
class AppNewContentNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artworkRepository: ArtworkRepository,
    private val bookCovers: BookCovers,
) : NewContentNotifier {

    /**
     * What the notification says; [cover] only for exactly one album, podcast or book, [actions] only for exactly
     * one new episode.
     */
    private class Message(
        val title: String,
        val text: String,
        val details: List<String>,
        val target: NewContentTarget,
        val cover: (suspend () -> Bitmap?)? = null,
        val actions: List<NotificationCompat.Action> = emptyList(),
    )

    // areNotificationsEnabled() also covers the missing POST_NOTIFICATIONS permission.
    @SuppressLint("MissingPermission")
    override suspend fun show(content: NewContent) {
        if (content.isEmpty) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannels(manager)
        val shown = content.withUnread(unread(manager, content.mode))
        val message = when (shown) {
            is NewContent.Music -> music(shown)
            is NewContent.Podcasts -> podcasts(shown)
            is NewContent.Books -> books(shown)
        }
        val mode = message.target.mode
        val open = Intent(context, MainActivity::class.java)
            .setAction(NewContentTarget.ACTION_OPEN)
            .putNewContentTarget(message.target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Not again after an app update ([HandledLaunches]); the notification disappears on the tap.
            .withLaunchId()
        // Separate requestCode per area: otherwise the notifications (and the playback one, code 0) would share one
        // PendingIntent, and FLAG_UPDATE_CURRENT would overwrite the others' target.
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE_BASE + mode.ordinal,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bigText = (listOf(message.text) + message.details).joinToString("\n")
        val notification = NotificationCompat.Builder(context, channelId(mode))
            .setSmallIcon(R.drawable.ic_stat_earloft)
            .setLargeIcon(message.cover?.let { loadCover(it) })
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .apply { message.actions.forEach(::addAction) }
            .addExtras(Bundle().apply { putString(EXTRA_CONTENT, NewContentJson.encode(shown)) })
            .build()
        manager.notify(notificationId(mode), notification)
    }

    /** What the area's notification still shows; `null` once it was tapped, swiped away or removed by the system. */
    private fun unread(manager: NotificationManagerCompat, mode: SpokenMode): NewContent? =
        manager.activeNotifications.firstOrNull { it.id == notificationId(mode) && it.packageName == context.packageName }
            ?.notification?.extras?.getString(EXTRA_CONTENT)
            ?.let(NewContentJson::decode)

    private val NewContent.mode: SpokenMode
        get() = when (this) {
            is NewContent.Music -> SpokenMode.MUSIC
            is NewContent.Podcasts -> SpokenMode.PODCASTS
            is NewContent.Books -> SpokenMode.BOOKS
        }

    private fun music(content: NewContent.Music): Message {
        val songs = content.albums.sumOf { it.songs }
        val songsText = context.resources.getQuantityString(R.plurals.new_content_songs, songs, songs)
        content.albums.singleOrNull()?.let { album ->
            return Message(
                title = album.title,
                text = listOf(album.artist, songsText).filter { it.isNotBlank() }.joinToString(" · "),
                details = emptyList(),
                target = NewContentTarget(SpokenMode.MUSIC, albumId = album.albumId),
                cover = { artworkRepository.albumCover(album.albumId, COVER_SIZE_PX) },
            )
        }
        val albums = content.albums.size
        return Message(
            title = context.getString(R.string.new_content_music_title),
            // One format string: the order of both parts may differ by language (review2 J86).
            text = context.getString(R.string.new_content_text, songsText, context.resources.getQuantityString(R.plurals.new_content_on_albums, albums, albums)),
            details = names(content.albums.map { album -> listOf(album.title, album.artist).filter { it.isNotBlank() }.joinToString(" – ") }),
            target = NewContentTarget(SpokenMode.MUSIC),
        )
    }

    private fun podcasts(content: NewContent.Podcasts): Message {
        // Moved subscriptions (concept 12.10 "feed move") appear as a separate line below the new episodes.
        val movedLine = content.moved.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.new_content_feeds_moved, it.joinToString(", ")) }
        if (content.episodes.isEmpty()) {
            val count = content.moved.size
            return Message(
                title = context.resources.getQuantityString(R.plurals.new_content_feeds_moved_title, count, count),
                text = movedLine.orEmpty(),
                details = emptyList(),
                target = NewContentTarget(SpokenMode.PODCASTS),
            )
        }
        val episodes = content.episodes.sumOf { it.count }
        val episodesText = context.resources.getQuantityString(R.plurals.new_content_episodes, episodes, episodes)
        // A single podcast is named and leads to its page; the image is that of the (newest) episode.
        // For exactly one new episode it can be played, queued as next and (without auto-download) downloaded right away.
        content.episodes.singleOrNull()?.let { feed ->
            return Message(
                title = feed.feedTitle,
                text = episodesText,
                details = listOfNotNull(movedLine),
                target = NewContentTarget(SpokenMode.PODCASTS, feedId = feed.feedId),
                cover = feed.episodeIds.firstOrNull()?.let { episodeId -> { bookCovers.cover(episodeId) } },
                actions = feed.episodeIds.singleOrNull()?.let { episodeActions(it, download = !feed.autoDownload) }.orEmpty(),
            )
        }
        val feeds = content.episodes.size
        return Message(
            title = context.getString(R.string.new_content_episodes_title),
            text = context.getString(R.string.new_content_text, episodesText, context.resources.getQuantityString(R.plurals.new_content_in_podcasts, feeds, feeds)),
            details = names(content.episodes.map { "${it.feedTitle} (${it.count})" }) + listOfNotNull(movedLine),
            target = NewContentTarget(SpokenMode.PODCASTS),
        )
    }

    /**
     * "Play" opens the app (where the mobile data prompt may come up) and the player; "Play next" and
     * "Download" run in the background ([NewEpisodeActionReceiver]).
     */
    private fun episodeActions(bookId: Long, download: Boolean): List<NotificationCompat.Action> {
        val play = Intent().setComponent(ComponentName(context, PLAY_ALIAS))
            .setAction(ACTION_PLAY_EPISODE)
            .putExtra(NewEpisodeActionReceiver.EXTRA_BOOK_ID, bookId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Otherwise an app update would play the episode again on the next start from the launcher ([HandledLaunches]).
            .withLaunchId()
        fun broadcast(action: String, requestCode: Int) = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NewEpisodeActionReceiver::class.java).setAction(action).putExtra(NewEpisodeActionReceiver.EXTRA_BOOK_ID, bookId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return listOfNotNull(
            NotificationCompat.Action(
                R.drawable.ic_stat_earloft,
                context.getString(R.string.new_content_play),
                PendingIntent.getActivity(context, REQUEST_CODE_PLAY, play, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ),
            NotificationCompat.Action(
                R.drawable.ic_stat_earloft,
                context.getString(R.string.new_content_play_next),
                broadcast(NewEpisodeActionReceiver.ACTION_PLAY_NEXT, REQUEST_CODE_PLAY_NEXT),
            ),
            if (download) {
                NotificationCompat.Action(
                    R.drawable.ic_stat_earloft,
                    context.getString(R.string.new_content_download),
                    broadcast(NewEpisodeActionReceiver.ACTION_DOWNLOAD, REQUEST_CODE_DOWNLOAD),
                )
            } else {
                null
            },
        )
    }

    private fun books(content: NewContent.Books): Message {
        val count = content.books.size
        val booksText = context.resources.getQuantityString(R.plurals.new_content_books, count, count)
        content.books.singleOrNull()?.let { book ->
            return Message(
                title = book.title,
                text = bookAuthorLine(book.author).ifBlank { booksText },
                details = emptyList(),
                target = NewContentTarget(SpokenMode.BOOKS, bookId = book.bookId),
                cover = { bookCovers.cover(book.bookId) },
            )
        }
        // Several new books of a subscribed show: it is named and leads to its page.
        content.books.mapNotNull { it.ardShow }.distinct().singleOrNull()?.takeIf { content.books.all { book -> book.ardShow == it } }?.let { show ->
            return Message(
                title = show.title,
                text = booksText,
                details = names(content.books.map { book -> listOf(book.title, bookAuthorLine(book.author)).filter { it.isNotBlank() }.joinToString(" – ") }),
                target = NewContentTarget(SpokenMode.BOOKS, ardShowId = show.id, ardShowTitle = show.title),
                cover = { bookCovers.cover(content.books.first().bookId) },
            )
        }
        return Message(
            title = context.getString(R.string.new_content_books_title),
            text = booksText,
            details = names(content.books.map { book -> listOf(book.title, bookAuthorLine(book.author)).filter { it.isNotBlank() }.joinToString(" – ") }),
            target = NewContentTarget(SpokenMode.BOOKS),
        )
    }

    /** The first [MAX_NAMES] names for the expanded notification, followed by "…" if there are more. */
    private fun names(all: List<String>): List<String> =
        all.take(MAX_NAMES) + if (all.size > MAX_NAMES) listOf("…") else emptyList()

    /**
     * Cover for the large icon: square crop from the center like the covers in the app (episode images are often wider) and
     * scaled down (otherwise large images would blow up the handover to the system).
     */
    private suspend fun loadCover(load: suspend () -> Bitmap?): Bitmap? = try {
        withTimeoutOrNull(COVER_TIMEOUT_MS) { load() }?.let { bitmap ->
            val side = minOf(bitmap.width, bitmap.height)
            val square = if (bitmap.width == bitmap.height) bitmap
            else Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            if (side > COVER_SIZE_PX) square.scale(COVER_SIZE_PX, COVER_SIZE_PX) else square
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Without a cover the notification is still correct.
        Log.w(TAG, "Cover for the notification could not be loaded", e)
        null
    }

    private fun createChannels(manager: NotificationManagerCompat) {
        // Until 2026-11 there was a shared channel for episodes and books.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannelsCompat(
            SpokenMode.entries.map { mode ->
                NotificationChannelCompat.Builder(channelId(mode), NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(channelName(mode)))
                    .build()
            },
        )
    }

    private fun channelId(mode: SpokenMode): String = when (mode) {
        SpokenMode.MUSIC -> "new_music"
        SpokenMode.PODCASTS -> "new_episodes"
        SpokenMode.BOOKS -> "new_books"
    }

    private fun channelName(mode: SpokenMode): Int = when (mode) {
        SpokenMode.MUSIC -> R.string.new_content_music_title
        SpokenMode.PODCASTS -> R.string.new_content_episodes_title
        SpokenMode.BOOKS -> R.string.new_content_books_title
    }

    companion object {
        /** "Play" from the new-episode notification; the `bookId` is in [NewEpisodeActionReceiver.EXTRA_BOOK_ID]. */
        const val ACTION_PLAY_EPISODE = "de.kleinabersmart.earloft.NEW_EPISODE_PLAY"

        /** Non-exported alias of [MainActivity] (manifest): "Play" starts an episode only through it (review D30). */
        val PLAY_ALIAS: String = MainActivity::class.java.name.substringBeforeLast('.') + ".NotificationPlay"

        fun notificationId(mode: SpokenMode): Int = NOTIFICATION_ID_BASE + mode.ordinal

        private const val TAG = "NewContentNotifier"

        /** The shown [NewContent] as JSON ([NewContentJson]), for the next notification of the area. */
        private const val EXTRA_CONTENT = "de.kleinabersmart.earloft.NEW_CONTENT"
        private const val LEGACY_CHANNEL_ID = "new_content"
        private const val NOTIFICATION_ID_BASE = 0x4e43
        private const val REQUEST_CODE_BASE = 0x4e43
        private const val REQUEST_CODE_PLAY = 0x4e50
        private const val REQUEST_CODE_PLAY_NEXT = 0x4e51
        private const val REQUEST_CODE_DOWNLOAD = 0x4e52
        private const val COVER_SIZE_PX = 256
        private const val COVER_TIMEOUT_MS = 15_000L
        private const val MAX_NAMES = 5
    }
}
