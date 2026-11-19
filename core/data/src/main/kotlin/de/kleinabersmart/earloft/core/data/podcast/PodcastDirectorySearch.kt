package de.kleinabersmart.earloft.core.data.podcast

import android.util.Log
import de.kleinabersmart.earloft.core.network.podcast.directory.DirectoryPodcast
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastCategory
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectoryApi
import de.kleinabersmart.earloft.core.network.podcast.directory.mergeDirectoryResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a directory search: the merged hits and the directories that didn't respond. */
data class DirectorySearchResult(val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>)

/** Search in the enabled public directories (concept 12.10). */
@Singleton
class PodcastDirectorySearch @Inject constructor(
    private val api: PodcastDirectoryApi,
    private val settings: PodcastSettings,
) {
    /** The directories this build knows (for the settings). */
    val available: List<PodcastDirectory> get() = api.available

    /** Enabled and available in this build, in display order. */
    val enabled: Flow<List<PodcastDirectory>> = settings.directories.map { on -> api.available.filter { it in on } }

    /** The enabled directories with charts or trends ("Discover", concept 12.10). */
    val discoverable: Flow<List<PodcastDirectory>> = enabled.map { list -> list.filter { it.discoverable } }

    /** The country of the Apple charts (as for search). */
    val country: Flow<String> = settings.appleCountry

    /** Queries all enabled directories at once; an error or timeout only affects that one. */
    suspend fun search(term: String): DirectorySearchResult {
        val country = settings.appleCountry.first()
        return ask(enabled.first(), "Search") { api.search(it, term, country) }
    }

    /**
     * Popular podcasts from the enabled directories with charts: Apple for the configured country, Podcast Index in
     * the device language; [category] narrows it down. Merged and deduplicated like the search.
     */
    suspend fun popular(category: PodcastCategory?, language: String = Locale.getDefault().language): DirectorySearchResult {
        val country = settings.appleCountry.first()
        return ask(discoverable.first(), "Charts") { api.popular(it, category, country, language) }
    }

    private suspend fun ask(
        directories: List<PodcastDirectory>,
        what: String,
        request: suspend (PodcastDirectory) -> List<DirectoryPodcast>,
    ): DirectorySearchResult = coroutineScope {
        val answers = directories.map { directory ->
            async { directory to fetch(directory, what) { request(directory) } }
        }.awaitAll()
        DirectorySearchResult(
            hits = mergeDirectoryResults(answers.mapNotNull { it.second }),
            failed = answers.filter { it.second == null }.mapTo(HashSet()) { it.first },
        )
    }

    private suspend fun fetch(directory: PodcastDirectory, what: String, request: suspend () -> List<DirectoryPodcast>): List<DirectoryPodcast>? = try {
        withTimeoutOrNull(TIMEOUT_MS) { request() }
            .also { if (it == null) Log.w(TAG, "$directory does not respond") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "$what in $directory failed", e)
        null
    }

    private companion object {
        const val TAG = "PodcastDirectorySearch"
        const val TIMEOUT_MS = 10_000L
    }
}
