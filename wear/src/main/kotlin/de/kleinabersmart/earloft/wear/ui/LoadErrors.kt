package de.kleinabersmart.earloft.wear.ui

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * As on the phone (concept 10): an exception from the source (usually Room) is logged and yields [failed] instead of
 * crashing the watch app. The flow then ends; the view shows [failed] until it is reopened.
 */
internal fun <T> Flow<T>.orOnError(failed: T): Flow<T> = catch { e ->
    Log.e(TAG, "Inhalt konnte nicht geladen werden", e)
    emit(failed)
}

private const val TAG = "LoadErrors"
