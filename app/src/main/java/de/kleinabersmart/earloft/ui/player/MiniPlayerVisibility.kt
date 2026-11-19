package de.kleinabersmart.earloft.ui.player

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ob der Nutzer den Mini-Player weggewischt hat (nur pausiert möglich). Bleibt über einen Neustart der App erhalten, bis wieder
 * etwas abgespielt wird ([PlayerViewModel] ruft dann [show]). Bewusst keine Einstellung: nicht in `ThemeSettings` und damit
 * nicht in der Sicherung.
 */
@Singleton
class MiniPlayerVisibility @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(prefs.getBoolean(KEY_HIDDEN, false))
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun hide() = set(true)

    fun show() = set(false)

    private fun set(hidden: Boolean) {
        if (_hidden.value == hidden) return
        _hidden.value = hidden
        prefs.edit { putBoolean(KEY_HIDDEN, hidden) }
    }

    private companion object {
        const val PREFS = "mini_player"
        const val KEY_HIDDEN = "hidden"
    }
}
