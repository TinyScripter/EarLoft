package keinabersmart.kleinaberlaut.ui

/** Zustand eines Detail-Bildschirms, dessen Inhalt gelöscht werden kann. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data object Missing : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
}

fun <T : Any> T?.toLoadState(): LoadState<T> = if (this == null) LoadState.Missing else LoadState.Ready(this)
