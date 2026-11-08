package de.kleinabersmart.earloft.wear.ui

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Wie am Handy (Konzept 10): Eine Ausnahme der Quelle (meist Room) wird geloggt und ergibt [failed], statt die Uhr-App zu
 * beenden. Danach endet der Fluss; die Ansicht zeigt [failed], bis sie neu geöffnet wird.
 */
internal fun <T> Flow<T>.orOnError(failed: T): Flow<T> = catch { e ->
    Log.e(TAG, "Inhalt konnte nicht geladen werden", e)
    emit(failed)
}

private const val TAG = "LoadErrors"
