package de.kleinabersmart.earloft.core.datasource.local

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

/** Reads [EmbeddedTags] with Media3 (spike S2: decision for `MetadataRetriever` instead of TagLib). */
@OptIn(UnstableApi::class)
internal class LocalTagReader(private val context: Context) {

    /** `null` if the file isn't readable. */
    suspend fun read(uri: Uri): EmbeddedTags? = metadata(uri, EmbeddedTags::from)

    /** Date, description, subtitle and chapters of an episode or book; `null` if the file isn't readable. */
    suspend fun readSpoken(uri: Uri): SpokenTags? = metadata(uri, SpokenTags::from)

    private suspend fun <T> metadata(uri: Uri, parse: (List<Metadata>) -> T): T? = try {
        withTimeout(TIMEOUT_MS) {
            MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().await()
                parse((0 until groups.length).mapNotNull { groups[it].takeIf { g -> g.length > 0 }?.getFormat(0)?.metadata })
            }
        }
    } catch (e: CancellationException) {
        // The timeout (TimeoutCancellationException) is also a CancellationException.
        if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
    } catch (e: Exception) {
        Log.w(TAG, "Tags of $uri unreadable", e)
        null
    }

    private companion object {
        const val TAG = "LocalTagReader"
        const val TIMEOUT_MS = 10_000L
    }
}
