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
 * Podcasts in the browse tree (concept 7.1): continue listening (the app's queue), new episodes, subscriptions, downloaded episodes. Archived
 * subscriptions, trial subscriptions and disabled servers are already excluded by the queries. [BrowseTree] checks whether podcasts are enabled.
 */
internal class PodcastBrowse @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val podcastRepository: PodcastRepository,
    private val bookRepository: BookRepository,
    private val continueListening: ContinueListening,
) {
    /** Content of a podcast folder; `null` if [parentId] isn't one. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == MediaIds.EPISODES_CONTINUE -> continueEpisodes(Int.MAX_VALUE)
        parentId == MediaIds.EPISODES_NEW -> episodeItems(podcastRepository.newestOpenEpisodes(NEW_LIMIT).first())
        parentId == MediaIds.FEEDS -> podcastRepository.feeds.first().map { it.toBrowsableMediaItem(context) }
        parentId == MediaIds.EPISODES_DOWNLOADED -> episodeItems(downloadedEpisodes())
        MediaIds.feedId(parentId) != null -> episodeItems(podcastRepository.episodes(MediaIds.feedId(parentId)!!).first())
        else -> null
    }

    /** "Continue listening", at most [limit] episodes. */
    suspend fun continueEpisodes(limit: Int): List<MediaItem> = episodeItems(continueListening.episodes.first().take(limit))

    /** Search results or other episodes as playable entries. */
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
     * The episode a podcast starts with on voice request: its first in "Continue listening", otherwise the newest one not
     * finished, otherwise the newest.
     */
    suspend fun startEpisodeOf(feedId: String): Long? {
        continueListening.episodes.first().firstOrNull { it.book.serverId == feedId }?.let { return it.book.id }
        val episodes = podcastRepository.episodes(feedId).first()
        return (episodes.firstOrNull { it.progress?.finished != true } ?: episodes.firstOrNull())?.book?.id
    }

    /** Fully downloaded episodes, newest first. */
    private suspend fun downloadedEpisodes(): List<BookWithProgress> =
        podcastRepository.downloadedIds().first()
            .mapNotNull { bookRepository.book(it).first() }
            .sortedByDescending { it.book.addedAt }

    private companion object {
        const val NEW_LIMIT = 100
    }
}

/**
 * Books in the browse tree (concept 7.1): continue listening, recently added, all, by author and by series. Disabled servers
 * are already excluded by the queries. [BrowseTree] checks whether books are enabled.
 */
internal class BookBrowse @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val episodeQueue: EpisodeQueue,
) {
    /** Content of a books folder; `null` if [parentId] isn't one. */
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

    /** "Continue listening" in the app's order, at most [limit] books. */
    suspend fun continueBooks(limit: Int): List<MediaItem> =
        bookItems(bookRepository.continueListening(limit, startOrder = episodeQueue.order).first())

    suspend fun bookItems(books: List<BookWithProgress>): List<MediaItem> {
        if (books.isEmpty()) return emptyList()
        val downloads = bookRepository.downloads().first()
        return books.map { it.toPlayableSpokenItem(context, it.book.authorLine, downloads[it.book.id]?.state) }
    }

    /** The first started, unfinished book (voice command without a term, if nothing else fits). */
    suspend fun firstStartedId(): Long? = bookRepository.continueListening(1, startOrder = episodeQueue.order).first().firstOrNull()?.book?.id

    private suspend fun allBooks(): List<BookWithProgress> = bookRepository.books().first()

    private companion object {
        const val LIST_LIMIT = 100
    }
}
