package de.kleinabersmart.earloft.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.MainActivity
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.notification.NewContentTarget.Companion.putNewContentTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet neue Musik, Folgen und Bücher als leise Benachrichtigung, je Bereich eine eigene (mit eigenem Kanal). Ein Tipp
 * öffnet Start im Bereich bzw. bei genau einem Album, Podcast oder Buch dessen Seite ([NewContentTarget]); dann steht
 * auch das Cover in der Benachrichtigung.
 */
@Singleton
class AppNewContentNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artworkRepository: ArtworkRepository,
    private val bookCovers: BookCovers,
) : NewContentNotifier {

    /** Was in der Benachrichtigung steht; [cover] nur bei genau einem Album, Podcast oder Buch. */
    private class Message(
        val title: String,
        val text: String,
        val details: List<String>,
        val target: NewContentTarget,
        val cover: (suspend () -> Bitmap?)? = null,
    )

    // areNotificationsEnabled() deckt auch die fehlende POST_NOTIFICATIONS-Berechtigung ab.
    @SuppressLint("MissingPermission")
    override suspend fun show(content: NewContent) {
        if (content.isEmpty) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannels(manager)
        val message = when (content) {
            is NewContent.Music -> music(content)
            is NewContent.Podcasts -> podcasts(content)
            is NewContent.Books -> books(content)
        }
        val mode = message.target.mode
        val open = Intent(context, MainActivity::class.java)
            .setAction(NewContentTarget.ACTION_OPEN)
            .putNewContentTarget(message.target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Eigener requestCode je Bereich: Sonst teilten sich die Benachrichtigungen (und die der Wiedergabe, Code 0) ein
        // PendingIntent, und FLAG_UPDATE_CURRENT überschriebe das Ziel der anderen.
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
            .build()
        manager.notify(NOTIFICATION_ID_BASE + mode.ordinal, notification)
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
            text = songsText + " " + context.resources.getQuantityString(R.plurals.new_content_on_albums, albums, albums),
            details = names(content.albums.map { album -> listOf(album.title, album.artist).filter { it.isNotBlank() }.joinToString(" – ") }),
            target = NewContentTarget(SpokenMode.MUSIC),
        )
    }

    private fun podcasts(content: NewContent.Podcasts): Message {
        val episodes = content.episodes.sumOf { it.count }
        val episodesText = context.resources.getQuantityString(R.plurals.new_content_episodes, episodes, episodes)
        // Ein einzelner Podcast nennt sich beim Namen und führt auf seine Seite; das Bild ist das der (neuesten) Folge.
        content.episodes.singleOrNull()?.let { feed ->
            return Message(
                title = feed.feedTitle,
                text = episodesText,
                details = emptyList(),
                target = NewContentTarget(SpokenMode.PODCASTS, feedId = feed.feedId),
                cover = feed.episodeIds.firstOrNull()?.let { episodeId -> { bookCovers.cover(episodeId) } },
            )
        }
        val feeds = content.episodes.size
        return Message(
            title = context.getString(R.string.new_content_episodes_title),
            text = episodesText + " " + context.resources.getQuantityString(R.plurals.new_content_in_podcasts, feeds, feeds),
            details = names(content.episodes.map { "${it.feedTitle} (${it.count})" }),
            target = NewContentTarget(SpokenMode.PODCASTS),
        )
    }

    private fun books(content: NewContent.Books): Message {
        val count = content.books.size
        val booksText = context.resources.getQuantityString(R.plurals.new_content_books, count, count)
        content.books.singleOrNull()?.let { book ->
            return Message(
                title = book.title,
                text = book.author.ifBlank { booksText },
                details = emptyList(),
                target = NewContentTarget(SpokenMode.BOOKS, bookId = book.bookId),
                cover = { bookCovers.cover(book.bookId) },
            )
        }
        return Message(
            title = context.getString(R.string.new_content_books_title),
            text = booksText,
            details = names(content.books.map { book -> listOf(book.title, book.author).filter { it.isNotBlank() }.joinToString(" – ") }),
            target = NewContentTarget(SpokenMode.BOOKS),
        )
    }

    /** Die ersten [MAX_NAMES] Namen für die aufgeklappte Benachrichtigung, darunter „…“, wenn es mehr sind. */
    private fun names(all: List<String>): List<String> =
        all.take(MAX_NAMES) + if (all.size > MAX_NAMES) listOf("…") else emptyList()

    /**
     * Cover für das große Symbol: quadratisch aus der Mitte wie die Cover in der App (Folgenbilder sind oft breiter) und
     * verkleinert (große Bilder sprengen sonst die Übergabe an das System).
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
        // Ohne Cover ist die Benachrichtigung immer noch richtig.
        Log.w(TAG, "Cover für die Benachrichtigung nicht ladbar", e)
        null
    }

    private fun createChannels(manager: NotificationManagerCompat) {
        // Bis 2026-11 gab es einen gemeinsamen Kanal für Folgen und Bücher.
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

    private companion object {
        const val TAG = "NewContentNotifier"
        const val LEGACY_CHANNEL_ID = "new_content"
        const val NOTIFICATION_ID_BASE = 0x4e43
        const val REQUEST_CODE_BASE = 0x4e43
        const val COVER_SIZE_PX = 256
        const val COVER_TIMEOUT_MS = 15_000L
        const val MAX_NAMES = 5
    }
}
