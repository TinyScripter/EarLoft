package de.kleinabersmart.tinyamp.core.network.subsonic

import java.io.IOException

/** Der Server hat mit `status = failed` geantwortet; [serverMessage] ist seine Meldung ohne Code. */
class SubsonicException(val code: Int, val serverMessage: String?) : IOException("Subsonic-Fehler $code: $serverMessage") {

    val isAuthError: Boolean get() = code == WRONG_CREDENTIALS || code == TOKEN_AUTH_NOT_SUPPORTED || code == NOT_AUTHORIZED

    companion object {
        const val GENERIC = 0
        const val WRONG_CREDENTIALS = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
        const val NOT_AUTHORIZED = 50
        const val NOT_FOUND = 70
    }
}

/** Die Antwort war kein Subsonic-JSON (falsche URL, Reverse-Proxy-Fehlerseite …). */
class NotSubsonicException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** HTTP-Fehler ohne Subsonic-Antwort. */
class HttpStatusException(val status: Int) : IOException("HTTP $status")
