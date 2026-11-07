package de.kleinabersmart.tinyamp.core.network.subsonic

import de.kleinabersmart.tinyamp.core.network.ServerApiException
import de.kleinabersmart.tinyamp.core.network.UnexpectedResponseException

/** Der Server hat mit `status = failed` geantwortet; [serverMessage] ist seine Meldung ohne Code. */
class SubsonicException(code: Int, serverMessage: String?) :
    ServerApiException(code, serverMessage, "Subsonic-Fehler $code: $serverMessage") {

    override val isAuthError: Boolean get() = code == WRONG_CREDENTIALS || code == TOKEN_AUTH_NOT_SUPPORTED || code == NOT_AUTHORIZED

    companion object {
        const val GENERIC = 0
        const val WRONG_CREDENTIALS = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
        const val NOT_AUTHORIZED = 50
        const val NOT_FOUND = 70
    }
}

/** Die Antwort war kein Subsonic-JSON (falsche URL, Reverse-Proxy-Fehlerseite …). */
class NotSubsonicException(message: String, cause: Throwable? = null) : UnexpectedResponseException(message, cause)
