package de.kleinabersmart.tinyamp.core.data.podcast

import android.util.Log
import de.kleinabersmart.tinyamp.core.network.podcast.directory.DirectoryPodcast
import de.kleinabersmart.tinyamp.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.tinyamp.core.network.podcast.directory.PodcastDirectoryApi
import de.kleinabersmart.tinyamp.core.network.podcast.directory.mergeDirectoryResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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

    /** Fragt alle eingeschalteten Verzeichnisse gleichzeitig; ein Fehler oder Zeitüberschreitung trifft nur das eine. */
    suspend fun search(term: String): DirectorySearchResult = coroutineScope {
        val directories = enabled.first()
        val country = settings.appleCountry.first()
        val answers = directories.map { directory ->
            async { directory to fetch(directory, term, country) }
        }.awaitAll()
        DirectorySearchResult(
            hits = mergeDirectoryResults(answers.mapNotNull { it.second }),
            failed = answers.filter { it.second == null }.mapTo(HashSet()) { it.first },
        )
    }

    private suspend fun fetch(directory: PodcastDirectory, term: String, country: String): List<DirectoryPodcast>? = try {
        withTimeoutOrNull(TIMEOUT_MS) { api.search(directory, term, country) }
            .also { if (it == null) Log.w(TAG, "$directory antwortet nicht") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Suche in $directory fehlgeschlagen", e)
        null
    }

    private companion object {
        const val TAG = "PodcastDirectorySearch"
        const val TIMEOUT_MS = 10_000L
    }
}
