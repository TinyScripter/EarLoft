package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.datasource.IncompleteScanException
import de.kleinabersmart.earloft.core.datasource.MediaStoreUnavailableException
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

    /** The library [libraryName] changed on the server while it was read; nothing was deleted, the next sync retries. */
    data class LibraryChanged(val libraryName: String) : SyncFailure

    /** The app may not read the music on the device (permission revoked). */
    data object PermissionMissing : SyncFailure

    /** The device's media library (MediaStore) can't be read right now. */
    data object LocalUnavailable : SyncFailure

    /** Everything else, e.g. a database error; [message] only for the log, the user sees a general text. */
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
            is IncompleteScanException -> LibraryChanged(e.libraryName)
            is MediaStoreUnavailableException -> LocalUnavailable
            is IOException -> Unreachable(e.message)
            is SecurityException -> PermissionMissing
            else -> Other(e.message ?: e.javaClass.simpleName)
        }
    }
}
