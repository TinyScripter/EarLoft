package de.kleinabersmart.earloft.core.download

import java.io.IOException

/**
 * Storing the file on this device failed (its folder, the partial file, renaming it), not the transfer: a download that gives up
 * after its retries then says "could not be stored", not "server not reachable" ([DownloadOutcome.Retry.failure]). The cause stays
 * in the chain, so a full storage is still recognized ([isNoSpace]).
 */
class DeviceStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Runs [block], a step on this device's storage; its [IOException] becomes a [DeviceStorageException]. */
internal inline fun <T> onDevice(block: () -> T): T =
    try {
        block()
    } catch (e: DeviceStorageException) {
        throw e
    } catch (e: IOException) {
        throw DeviceStorageException(e.message ?: e.javaClass.simpleName, e)
    }
