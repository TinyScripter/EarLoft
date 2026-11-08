package de.kleinabersmart.earloft.ui

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Zustand eines Bildschirms (Konzept 10): lädt, gelöscht, fertig oder nicht lesbar. „Leer“ ist ein [Ready] mit leerer Liste;
 * so blitzt beim Öffnen kein Leer-Hinweis auf, bevor die Datenbank geantwortet hat.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    /** Der Inhalt einer Detailseite wurde gelöscht. */
    data object Missing : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>

    /** Die Quelle (meist die Datenbank) hat eine Ausnahme geworfen; sie steht im Log, nicht im Zustand. */
    data object Failed : LoadState<Nothing>
}

/** Der Inhalt, solange er geladen ist. */
val <T> LoadState<T>.valueOrNull: T? get() = (this as? LoadState.Ready)?.value

fun <T : Any> T?.toLoadState(): LoadState<T> = if (this == null) LoadState.Missing else LoadState.Ready(this)

/** Jeder Wert wird [LoadState.Ready]; eine Ausnahme der Quelle wird [LoadState.Failed], statt die App zu beenden. */
fun <T> Flow<T>.asLoadState(): Flow<LoadState<T>> = map<T, LoadState<T>> { LoadState.Ready(it) }.failedOnError()

/** Wie [asLoadState]; `null` heißt gelöscht ([LoadState.Missing]). */
fun <T : Any> Flow<T?>.asLoadStateOrMissing(): Flow<LoadState<T>> = map { it.toLoadState() }.failedOnError()

/** Für zusammengesetzte Zustände: Eine Ausnahme der Quelle ergibt [failed], statt die App zu beenden. */
fun <T> Flow<T>.orOnError(failed: T): Flow<T> = catch { e ->
    Log.e(TAG, "Inhalt konnte nicht geladen werden", e)
    emit(failed)
}

private fun <T> Flow<LoadState<T>>.failedOnError(): Flow<LoadState<T>> = orOnError(LoadState.Failed)

private const val TAG = "LoadState"
