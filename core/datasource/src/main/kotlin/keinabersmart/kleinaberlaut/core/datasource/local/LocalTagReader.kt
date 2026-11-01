package keinabersmart.kleinaberlaut.core.datasource.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeout

/** Liest [EmbeddedTags] mit Media3 (Spike S2: Entscheidung für `MetadataRetriever` statt TagLib). */
@OptIn(UnstableApi::class)
internal class LocalTagReader(private val context: Context) {

    /** `null`, wenn die Datei nicht lesbar ist. */
    suspend fun read(uri: Uri): EmbeddedTags? = metadata(uri, EmbeddedTags::from)

    /** Datum, Beschreibung und Kapitel einer Podcast-Folge; `null`, wenn die Datei nicht lesbar ist. */
    suspend fun readEpisode(uri: Uri): EpisodeTags? = metadata(uri, EpisodeTags::from)

    private suspend fun <T> metadata(uri: Uri, parse: (List<Metadata>) -> T): T? = try {
        withTimeout(TIMEOUT_MS) {
            MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().await()
                parse((0 until groups.length).mapNotNull { groups[it].takeIf { g -> g.length > 0 }?.getFormat(0)?.metadata })
            }
        }
    } catch (e: CancellationException) {
        // Auch das Zeitlimit (TimeoutCancellationException) ist eine CancellationException.
        if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
    } catch (e: Exception) {
        Log.w(TAG, "Tags von $uri nicht lesbar", e)
        null
    }

    private companion object {
        const val TAG = "LocalTagReader"
        const val TIMEOUT_MS = 10_000L
    }
}
