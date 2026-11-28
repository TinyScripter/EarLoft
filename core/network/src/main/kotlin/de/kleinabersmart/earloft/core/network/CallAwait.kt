package de.kleinabersmart.earloft.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The request is answered by the server and its answer is not written to the HTTP cache. For API calls (JSON of Subsonic, Jellyfin
 * and Audiobookshelf): the app stores what it needs itself in its database, while a full sync wrote many MB of JSON into the shared
 * 100 MB cache, pushed out the covers kept there for offline use and left the Audiobookshelf token of `GET /api/me` in plain text
 * on disk (review5 P6). Not for covers and streams.
 */
internal fun Request.notStored(): Request = newBuilder().cacheControl(API_NOT_STORED).build()

private val API_NOT_STORED: CacheControl = CacheControl.Builder().noStore().build()

/** Executes the call asynchronously; cancelling the coroutine also cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
