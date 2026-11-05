package de.kleinabersmart.kleinaberlaut.core.data.sync

import de.kleinabersmart.kleinaberlaut.core.network.subsonic.HttpStatusException
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.NotSubsonicException
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.SubsonicException
import java.io.IOException

/**
 * Warum der letzte Abgleich einer Quelle fehlschlug – nur Texte, keine Ausnahme samt Stacktrace (Review S2).
 * Die Einteilung entspricht [de.kleinabersmart.kleinaberlaut.core.data.repository.ConnectionResult].
 */
sealed interface SyncFailure {
    data object WrongCredentials : SyncFailure
    data object NotSubsonic : SyncFailure
    data class Unreachable(val reason: String?) : SyncFailure
    data class ServerError(val code: Int, val message: String?) : SyncFailure

    /** Alles andere, etwa ein Datenbankfehler. */
    data class Other(val message: String) : SyncFailure

    companion object {
        fun of(e: Exception): SyncFailure = when (e) {
            is SubsonicException -> if (e.isAuthError) WrongCredentials else ServerError(e.code, e.serverMessage)
            is NotSubsonicException -> NotSubsonic
            is HttpStatusException -> if (e.status == 404) NotSubsonic else Unreachable(e.message)
            is IOException -> Unreachable(e.message)
            else -> Other(e.message ?: e.javaClass.simpleName)
        }
    }
}
