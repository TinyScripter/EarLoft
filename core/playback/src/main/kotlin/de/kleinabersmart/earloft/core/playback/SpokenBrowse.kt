package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.media3.common.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.authorGroups
import de.kleinabersmart.earloft.core.data.spoken.seriesGroups
import de.kleinabersmart.earloft.core.model.authorLine
import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Podcasts im Browse-Tree (Konzept 7.1): Weiterhören (die Warteschlange der App), neue Folgen, Abos, geladene Folgen. Archivierte
 * Abos, Probeabos und ausgeschaltete Server fehlen schon in den Abfragen. Ob Podcasts eingeschaltet sind, prüft [BrowseTree].
 */
internal class PodcastBrowse @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val podcastRepository: PodcastRepository,
    private val bookRepository: BookRepository,
    private val continueListening: ContinueListening,
) {
    /** Inhalt eines Podcast-Ordners; `null`, wenn [parentId] keiner ist. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == MediaIds.EPISODES_CONTINUE -> continueEpisodes(Int.MAX_VALUE)
        parentId == MediaIds.EPISODES_NEW -> episodeItems(podcastRepository.recentEpisodes(NEW_LIMIT).first())
        parentId == MediaIds.FEEDS -> podcastRepository.feeds.first().map { it.toBrowsableMediaItem(context) }
        parentId == MediaIds.EPISODES_DOWNLOADED -> episodeItems(downloadedEpisodes())
        MediaIds.feedId(parentId) != null -> episodeItems(podcastRepository.episodes(MediaIds.feedId(parentId)!!).first())
        else -> null
    }

    /** „Weiterhören“, höchstens [limit] Folgen. */
    suspend fun continueEpisodes(limit: Int): List<MediaItem> = episodeItems(continueListening.episodes.first().take(limit))

    /** Treffer der Suche bzw. andere Folgen als abspielbare Einträge. */
    suspend fun episodeItems(episodes: List<BookWithProgress>): List<MediaItem> {
        if (episodes.isEmpty()) return emptyList()
        val feedTitles = podcastRepository.feeds.first().associate { it.id to it.title }
        val downloads = podcastRepository.episodeDownloadStates().first()
        return episodes.map { episode ->
            val subtitle = feedTitles[episode.book.serverId] ?: episode.book.author
            episode.toPlayableSpokenItem(context, subtitle, downloads[episode.book.id])
        }
    }

    /**
     * Die Folge, mit der ein Podcast auf Zuruf startet: seine erste in „Weiterhören“, sonst die neueste nicht zu Ende
     * gehörte, sonst die neueste.
     */
    suspend fun startEpisodeOf(feedId: String): Long? {
        continueListening.episodes.first().firstOrNull { it.book.serverId == feedId }?.let { return it.book.id }
        val episodes = podcastRepository.episodes(feedId).first()
        return (episodes.firstOrNull { it.progress?.finished != true } ?: episodes.firstOrNull())?.book?.id
    }

    /** Fertig geladene Folgen, neueste zuerst. */
    private suspend fun downloadedEpisodes(): List<BookWithProgress> =
        podcastRepository.downloadedIds().first()
            .mapNotNull { bookRepository.book(it).first() }
            .sortedByDescending { it.book.addedAt }

    private companion object {
        const val NEW_LIMIT = 100
    }
}

/**
 * Bücher im Browse-Tree (Konzept 7.1): Weiterhören, zuletzt hinzugefügt, alle, nach Autor und nach Serie. Ausgeschaltete Server
 * fehlen schon in den Abfragen. Ob Bücher eingeschaltet sind, prüft [BrowseTree].
 */
internal class BookBrowse @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val episodeQueue: EpisodeQueue,
) {
    /** Inhalt eines Bücher-Ordners; `null`, wenn [parentId] keiner ist. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == MediaIds.BOOKS_CONTINUE -> continueBooks(LIST_LIMIT)
        parentId == MediaIds.BOOKS_RECENT -> bookItems(bookRepository.recentlyAdded(LIST_LIMIT).first())
        parentId == MediaIds.BOOKS_ALL -> bookItems(allBooks())
        parentId == MediaIds.AUTHORS -> allBooks().authorGroups().map { it.toBrowsableMediaItem(context, MediaIds.author(it.name)) }
        parentId == MediaIds.SERIES -> allBooks().seriesGroups().map { it.toBrowsableMediaItem(context, MediaIds.series(it.name)) }
        MediaIds.authorName(parentId) != null -> {
            val name = MediaIds.authorName(parentId)!!
            bookItems(allBooks().authorGroups().firstOrNull { it.name == name }?.books.orEmpty())
        }
        MediaIds.seriesName(parentId) != null -> {
            val name = MediaIds.seriesName(parentId)!!
            bookItems(allBooks().seriesGroups().firstOrNull { it.name == name }?.books.orEmpty())
        }
        else -> null
    }

    /** „Weiterhören“ in der Reihenfolge der App, höchstens [limit] Bücher. */
    suspend fun continueBooks(limit: Int): List<MediaItem> =
        bookItems(bookRepository.continueListening(limit, startOrder = episodeQueue.order).first())

    suspend fun bookItems(books: List<BookWithProgress>): List<MediaItem> {
        if (books.isEmpty()) return emptyList()
        val downloads = bookRepository.downloads().first()
        return books.map { it.toPlayableSpokenItem(context, it.book.authorLine, downloads[it.book.id]?.state) }
    }

    /** Das erste angefangene, nicht beendete Buch (Sprachbefehl ohne Begriff, wenn sonst nichts passt). */
    suspend fun firstStartedId(): Long? = bookRepository.continueListening(1, startOrder = episodeQueue.order).first().firstOrNull()?.book?.id

    private suspend fun allBooks(): List<BookWithProgress> = bookRepository.books().first()

    private companion object {
        const val LIST_LIMIT = 100
    }
}
