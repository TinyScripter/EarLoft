package de.kleinabersmart.earloft.ui.player

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Whether the user has swiped the mini player away (only possible while paused). Persists across an app restart until
 * something is played again ([PlayerViewModel] then calls [show]). That may also happen while no screen observes playback
 * (Android Auto, the watch, the notification): that's why the title and position at hiding are kept, and [showIfMovedOn] brings the
 * mini player back when the paused state differs from them. Deliberately not a setting: not in `ThemeSettings` and thus
 * not in the backup.
 */
@Singleton
class MiniPlayerVisibility @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(prefs.getBoolean(KEY_HIDDEN, false))
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    /** [itemKey] and [positionMs]: what is loaded (paused) while hiding. */
    fun hide(itemKey: String?, positionMs: Long) {
        prefs.edit {
            putString(KEY_ITEM, itemKey)
            putLong(KEY_POSITION, positionMs)
        }
        set(true)
    }

    fun show() = set(false)

    /** Shows the mini player again if another title is loaded or the position has moved since hiding (played elsewhere). */
    fun showIfMovedOn(itemKey: String?, positionMs: Long) {
        if (!_hidden.value) return
        val moved = itemKey != prefs.getString(KEY_ITEM, null) ||
            abs(positionMs - prefs.getLong(KEY_POSITION, positionMs)) > POSITION_TOLERANCE_MS
        if (moved) show()
    }

    private fun set(hidden: Boolean) {
        if (_hidden.value == hidden) return
        _hidden.value = hidden
        prefs.edit { putBoolean(KEY_HIDDEN, hidden) }
    }

    private companion object {
        const val PREFS = "mini_player"
        const val KEY_HIDDEN = "hidden"
        const val KEY_ITEM = "item"
        const val KEY_POSITION = "position"

        /**
         * Rounding of a saved and restored position. Not more: playing elsewhere often starts with the rewind after a pause,
         * a short listen can end close to where it was hidden (seen: 8 s played, 2.3 s before the old position).
         */
        const val POSITION_TOLERANCE_MS = 1_000L
    }
}
