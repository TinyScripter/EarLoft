package de.kleinabersmart.earloft.core.network

import java.io.IOException

/**
 * Ein Musikserver hat die Anfrage verstanden und als Fehler beantwortet; [serverMessage] ist seine
 * Meldung ohne Code. Die Bedeutung von [code] hängt von der Server-Art ab.
 */
abstract class ServerApiException(
    val code: Int,
    val serverMessage: String?,
    message: String,
) : IOException(message) {
    /** Zugangsdaten falsch oder abgelaufen – Aktionen warten, bis sie korrigiert sind. */
    abstract val isAuthError: Boolean
}

/** Die Antwort passt nicht zur erwarteten Server-Art (falsche URL, Fehlerseite eines Reverse-Proxys …). */
open class UnexpectedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * HTTP-Fehler ohne auswertbare Antwort des Servers. [movedTo]: Die Anfrage wurde vorher dauerhaft dorthin weitergeleitet, der Fehler
 * kam von dort (ein umgezogener geschützter Feed, dessen Zugangsdaten für den neuen Host noch fehlen).
 */
class HttpStatusException(val status: Int, val movedTo: String? = null) : IOException("HTTP $status") {
    /** 401: Server, die Fehler per Statuscode melden, lehnen so falsche oder abgelaufene Zugangsdaten ab. */
    val isAuthError: Boolean get() = status == 401
}

/** Der Server hat die Zugangsdaten abgelehnt ([ServerApiException.isAuthError], [HttpStatusException.isAuthError]). */
val Throwable.isAuthRejection: Boolean
    get() = (this as? ServerApiException)?.isAuthError == true || (this as? HttpStatusException)?.isAuthError == true
