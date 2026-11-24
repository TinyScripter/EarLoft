package de.kleinabersmart.earloft.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * May a request to this URL carry the credentials of the server at [baseUrl]? When OkHttp follows a redirect,
 * the new request keeps the request tag with the credentials. They must not go along to another host or port (e.g. the
 * login page of an upstream proxy), nor from HTTPS to HTTP. HTTP → HTTPS on the same host
 * is fine, even if the port changes.
 */
fun HttpUrl.acceptsCredentialsOf(baseUrl: HttpUrl): Boolean =
    host == baseUrl.host && if (isHttps == baseUrl.isHttps) port == baseUrl.port else isHttps

/**
 * Of several servers that may take [url] (each [acceptsCredentialsOf] it), the one at exactly this scheme and port goes first:
 * with two accounts on one host, `http://host:4533/` must not take the requests of `https://host/` only because it was
 * registered first (review3 Q20). Among equals [tieBreak] decides (e.g. the longest base path), otherwise the first.
 */
fun <T> Iterable<T>.bestFor(url: HttpUrl, baseUrl: (T) -> HttpUrl, tieBreak: (T) -> Int = { 0 }): T? =
    maxWithOrNull(compareBy<T>({ baseUrl(it).let { base -> base.scheme == url.scheme && base.port == url.port } }, tieBreak))

/**
 * Editing a server: may the stored password, key or sign-in of the address [previousBaseUrl] be used at this new
 * address without entering it again? Same rule as [acceptsCredentialsOf], so a typo in the host never sends them
 * elsewhere (review J28).
 */
fun HttpUrl.keepsCredentialsOf(previousBaseUrl: String?): Boolean =
    previousBaseUrl?.toHttpUrlOrNull()?.let { acceptsCredentialsOf(it) } == true
