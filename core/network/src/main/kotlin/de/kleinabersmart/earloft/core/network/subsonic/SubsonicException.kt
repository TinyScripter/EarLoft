package de.kleinabersmart.earloft.core.network.subsonic

import de.kleinabersmart.earloft.core.network.ServerApiException
import de.kleinabersmart.earloft.core.network.UnexpectedResponseException
import de.kleinabersmart.earloft.core.network.subsonic.dto.SubsonicEnvelope
import kotlinx.serialization.SerializationException

/** The server answered with `status = failed`; [serverMessage] is its message without the code. */
class SubsonicException(code: Int, serverMessage: String?) :
    ServerApiException(code, serverMessage, "Subsonic error $code" + serverMessage?.let { ": $it" }.orEmpty()) {

    override val isAuthError: Boolean
        get() = isRejectedCredentials || code == TOKEN_AUTH_NOT_SUPPORTED || code == NOT_AUTHORIZED

    /**
     * Password or API key rejected: new credentials may help. With [TOKEN_AUTH_NOT_SUPPORTED] and
     * [NOT_AUTHORIZED] (missing permission) they change nothing.
     */
    val isRejectedCredentials: Boolean get() = code == WRONG_CREDENTIALS || code == INVALID_API_KEY

    companion object {
        const val GENERIC = 0
        const val WRONG_CREDENTIALS = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
        /** OpenSubsonic extension `apiKeyAuthentication`. */
        const val INVALID_API_KEY = 44
        const val NOT_AUTHORIZED = 50
        const val NOT_FOUND = 70
    }
}

/**
 * Subsonic also reports errors on file endpoints (stream, download) as JSON with HTTP 200. Returns the error from such
 * a response; `null` if [body] is not a Subsonic error response.
 */
fun subsonicErrorIn(body: String): SubsonicException? {
    val response = try {
        SubsonicApi.json.decodeFromString<SubsonicEnvelope>(body).response
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (response.status == "ok") return null
    return SubsonicException(response.error?.code ?: SubsonicException.GENERIC, response.error?.message)
}

/** The response was not Subsonic JSON (wrong URL, reverse proxy error page …). */
class NotSubsonicException(message: String, cause: Throwable? = null) : UnexpectedResponseException(message, cause)
