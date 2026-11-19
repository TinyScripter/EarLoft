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
 * Whether the user has swiped the mini player away (only possible while paused). Persists across an app restart until
 * something is played again ([PlayerViewModel] then calls [show]). Deliberately not a setting: not in `ThemeSettings` and thus
 * not in the backup.
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
