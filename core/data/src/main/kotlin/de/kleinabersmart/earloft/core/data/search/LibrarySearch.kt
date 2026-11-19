package de.kleinabersmart.earloft.core.data.search

import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.SearchResults
import de.kleinabersmart.earloft.core.model.SeriesHit
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Results of a search across music, podcasts and books; sections that were not searched are empty. */
data class AreaSearchResults(
    val music: SearchResults = SearchResults.EMPTY,
    val feeds: List<PodcastFeed> = emptyList(),
    val episodes: List<BookWithProgress> = emptyList(),
    val books: List<BookWithProgress> = emptyList(),
    val authors: List<AuthorHit> = emptyList(),
    val narrators: List<AuthorHit> = emptyList(),
    val series: List<SeriesHit> = emptyList(),
) {
    val isEmpty: Boolean
        get() = music.isEmpty && feeds.isEmpty() && episodes.isEmpty() && books.isEmpty() && authors.isEmpty() &&
            narrators.isEmpty() && series.isEmpty()

    companion object {
        val EMPTY = AreaSearchResults()
    }
}

/**
 * The single search for the app, Android Auto and voice commands (concept 7.1/12.5). Disabled sections (General → Sections)
 * are never searched, even if the caller requests them.
 */
@Singleton
class LibrarySearch @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val bookRepository: BookRepository,
    private val podcastRepository: PodcastRepository,
    private val spokenSettings: SpokenSettings,
) {
    /** Searches [query] in [areas] (default: all), as far as they are enabled. */
    suspend fun search(query: String, areas: Collection<SpokenMode> = SpokenMode.entries): AreaSearchResults {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return AreaSearchResults.EMPTY
        val enabled = spokenSettings.areas.first()
        val music = SpokenMode.MUSIC in areas && SpokenMode.MUSIC in enabled
        val podcasts = SpokenMode.PODCASTS in areas && SpokenMode.PODCASTS in enabled
        val books = SpokenMode.BOOKS in areas && SpokenMode.BOOKS in enabled
        return AreaSearchResults(
            music = if (music) libraryRepository.search(trimmed) else SearchResults.EMPTY,
            feeds = if (podcasts) podcastRepository.searchFeeds(trimmed) else emptyList(),
            episodes = if (podcasts) bookRepository.search(trimmed, BookKind.EPISODE) else emptyList(),
            books = if (books) bookRepository.search(trimmed, BookKind.BOOK) else emptyList(),
            authors = if (books) bookRepository.searchAuthors(trimmed) else emptyList(),
            narrators = if (books) bookRepository.searchNarrators(trimmed) else emptyList(),
            series = if (books) bookRepository.searchSeries(trimmed) else emptyList(),
        )
    }
}
