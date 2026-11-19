package de.kleinabersmart.earloft.core.network

import java.io.IOException

/**
 * A music server understood the request and answered with an error; [serverMessage] is its
 * message without the code. The meaning of [code] depends on the server type.
 */
abstract class ServerApiException(
    val code: Int,
    val serverMessage: String?,
    message: String,
) : IOException(message) {
    /** Credentials wrong or expired – actions wait until they are corrected. */
    abstract val isAuthError: Boolean
}

/** The response doesn't match the expected server type (wrong URL, error page of a reverse proxy …). */
open class UnexpectedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * HTTP error without an evaluable response from the server. [movedTo]: the request was permanently redirected there beforehand, the error
 * came from there (a protected feed that moved, whose credentials for the new host are still missing).
 */
class HttpStatusException(val status: Int, val movedTo: String? = null) : IOException("HTTP $status") {
    /** 401: servers that report errors via status code reject wrong or expired credentials this way. */
    val isAuthError: Boolean get() = status == 401
}

/** The server rejected the credentials ([ServerApiException.isAuthError], [HttpStatusException.isAuthError]). */
val Throwable.isAuthRejection: Boolean
    get() = (this as? ServerApiException)?.isAuthError == true || (this as? HttpStatusException)?.isAuthError == true
