package de.kleinabersmart.earloft.ui

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * State of a screen (concept 10): loading, deleted, ready or unreadable. "Empty" is a [Ready] with an empty list;
 * that way no empty hint flashes up on opening before the database has answered.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    /** The content of a detail page was deleted. */
    data object Missing : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>

    /** The source (usually the database) threw an exception; it is in the log, not in the state. */
    data object Failed : LoadState<Nothing>
}

/** The content, as long as it is loaded. */
val <T> LoadState<T>.valueOrNull: T? get() = (this as? LoadState.Ready)?.value

fun <T : Any> T?.toLoadState(): LoadState<T> = if (this == null) LoadState.Missing else LoadState.Ready(this)

/** Every value becomes [LoadState.Ready]; an exception from the source becomes [LoadState.Failed] instead of terminating the app. */
fun <T> Flow<T>.asLoadState(): Flow<LoadState<T>> = map<T, LoadState<T>> { LoadState.Ready(it) }.failedOnError()

/** Like [asLoadState]; `null` means deleted ([LoadState.Missing]). */
fun <T : Any> Flow<T?>.asLoadStateOrMissing(): Flow<LoadState<T>> = map { it.toLoadState() }.failedOnError()

/** For combined states: an exception from the source yields [failed] instead of terminating the app. */
fun <T> Flow<T>.orOnError(failed: T): Flow<T> = catch { e ->
    Log.e(TAG, "Content could not be loaded", e)
    emit(failed)
}

private fun <T> Flow<LoadState<T>>.failedOnError(): Flow<LoadState<T>> = orOnError(LoadState.Failed)

private const val TAG = "LoadState"
