package de.kleinabersmart.earloft.core.network

import okhttp3.HttpUrl

/**
 * May a request to this URL carry the credentials of the server at [baseUrl]? When OkHttp follows a redirect,
 * the new request keeps the request tag with the credentials. They must not go along to another host or port (e.g. the
 * login page of an upstream proxy), nor from HTTPS to HTTP. HTTP → HTTPS on the same host
 * is fine, even if the port changes.
 */
fun HttpUrl.acceptsCredentialsOf(baseUrl: HttpUrl): Boolean =
    host == baseUrl.host && if (isHttps == baseUrl.isHttps) port == baseUrl.port else isHttps
