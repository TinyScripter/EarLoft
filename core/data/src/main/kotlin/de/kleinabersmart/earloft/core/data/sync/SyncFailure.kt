package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.ServerApiException
import de.kleinabersmart.earloft.core.network.UnexpectedResponseException
import java.io.IOException

/**
 * Why the last sync of a source failed – only texts, no exception with stack trace (review S2).
 * The classification corresponds to [de.kleinabersmart.earloft.core.data.repository.ConnectionResult].
 */
sealed interface SyncFailure {
    data object WrongCredentials : SyncFailure

    /** No server of the configured type responds at the address. */
    data object UnexpectedServer : SyncFailure
    data class Unreachable(val reason: String?) : SyncFailure
    data class ServerError(val code: Int, val message: String?) : SyncFailure

    /** Everything else, e.g. a database error. */
    data class Other(val message: String) : SyncFailure

    companion object {
        fun of(e: Exception): SyncFailure = when (e) {
            is ServerApiException -> if (e.isAuthError) WrongCredentials else ServerError(e.code, e.serverMessage)
            is UnexpectedResponseException -> UnexpectedServer
            is HttpStatusException -> when {
                e.isAuthError -> WrongCredentials
                e.status == 404 -> UnexpectedServer
                else -> Unreachable(e.message)
            }
            is IOException -> Unreachable(e.message)
            else -> Other(e.message ?: e.javaClass.simpleName)
        }
    }
}
