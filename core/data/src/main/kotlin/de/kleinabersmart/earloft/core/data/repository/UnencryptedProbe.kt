package de.kleinabersmart.earloft.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether something answers at an address over unencrypted HTTP (review G63): an address typed without a scheme becomes
 * `https://`, and against a server that only speaks HTTP the form only said "unreachable". Its own client without any
 * interceptor, so no credentials of a known server, feed or token ever go along; nothing is connected, only asked.
 */
@Singleton
class UnencryptedProbe @Inject constructor() {
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** [input] as typed (without scheme). `false` also if the server only redirects to HTTPS. */
    suspend fun answers(input: String): Boolean = withContext(Dispatchers.IO) {
        val url = "http://${input.trim()}".toHttpUrlOrNull() ?: return@withContext false
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val toHttps = response.isRedirect && response.header("Location")?.startsWith("https:", ignoreCase = true) == true
                !toHttps
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_S = 5L
    }
}
