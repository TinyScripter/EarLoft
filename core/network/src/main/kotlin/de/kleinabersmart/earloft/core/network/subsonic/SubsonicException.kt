package de.kleinabersmart.earloft.core.network.subsonic

import de.kleinabersmart.earloft.core.network.ServerApiException
import de.kleinabersmart.earloft.core.network.UnexpectedResponseException
import de.kleinabersmart.earloft.core.network.subsonic.dto.SubsonicEnvelope
import kotlinx.serialization.SerializationException

/** Der Server hat mit `status = failed` geantwortet; [serverMessage] ist seine Meldung ohne Code. */
class SubsonicException(code: Int, serverMessage: String?) :
    ServerApiException(code, serverMessage, "Subsonic-Fehler $code" + serverMessage?.let { ": $it" }.orEmpty()) {

    override val isAuthError: Boolean
        get() = isRejectedCredentials || code == TOKEN_AUTH_NOT_SUPPORTED || code == NOT_AUTHORIZED

    /**
     * Passwort bzw. API-Schlüssel abgelehnt: Neue Zugangsdaten können helfen. Bei [TOKEN_AUTH_NOT_SUPPORTED] und
     * [NOT_AUTHORIZED] (fehlende Berechtigung) ändern sie nichts.
     */
    val isRejectedCredentials: Boolean get() = code == WRONG_CREDENTIALS || code == INVALID_API_KEY

    companion object {
        const val GENERIC = 0
        const val WRONG_CREDENTIALS = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
        /** OpenSubsonic-Extension `apiKeyAuthentication`. */
        const val INVALID_API_KEY = 44
        const val NOT_AUTHORIZED = 50
        const val NOT_FOUND = 70
    }
}

/**
 * Subsonic meldet Fehler auch bei Datei-Endpunkten (Stream, Download) als JSON mit HTTP 200. Liefert den Fehler aus so
 * einer Antwort; `null`, wenn [body] keine Subsonic-Fehlerantwort ist.
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

/** Die Antwort war kein Subsonic-JSON (falsche URL, Reverse-Proxy-Fehlerseite …). */
class NotSubsonicException(message: String, cause: Throwable? = null) : UnexpectedResponseException(message, cause)
