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

/** Ergebnis einer Verzeichnissuche: die gemischten Treffer und die Verzeichnisse, die nicht geantwortet haben. */
data class DirectorySearchResult(val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>)

/** Suche in den eingeschalteten öffentlichen Verzeichnissen (Konzept 12.10). */
@Singleton
class PodcastDirectorySearch @Inject constructor(
    private val api: PodcastDirectoryApi,
    private val settings: PodcastSettings,
) {
    /** Die Verzeichnisse, die dieser Build kennt (für die Einstellungen). */
    val available: List<PodcastDirectory> get() = api.available

    /** Eingeschaltet und in diesem Build vorhanden, in Anzeigereihenfolge. */
    val enabled: Flow<List<PodcastDirectory>> = settings.directories.map { on -> api.available.filter { it in on } }

    /** Die eingeschalteten Verzeichnisse mit Charts bzw. Trends („Entdecken“, Konzept 12.10). */
    val discoverable: Flow<List<PodcastDirectory>> = enabled.map { list -> list.filter { it.discoverable } }

    /** Das Land der Apple-Charts (wie bei der Suche). */
    val country: Flow<String> = settings.appleCountry

    /** Fragt alle eingeschalteten Verzeichnisse gleichzeitig; ein Fehler oder Zeitüberschreitung trifft nur das eine. */
    suspend fun search(term: String): DirectorySearchResult {
        val country = settings.appleCountry.first()
        return ask(enabled.first(), "Suche") { api.search(it, term, country) }
    }

    /**
     * Beliebte Podcasts aus den eingeschalteten Verzeichnissen mit Charts: Apple für das eingestellte Land, Podcast Index in
     * der Sprache des Geräts; [category] schränkt ein. Gemischt und zusammengelegt wie die Suche.
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
            .also { if (it == null) Log.w(TAG, "$directory antwortet nicht") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "$what in $directory fehlgeschlagen", e)
        null
    }

    private companion object {
        const val TAG = "PodcastDirectorySearch"
        const val TIMEOUT_MS = 10_000L
    }
}
