package de.kleinabersmart.earloft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import de.kleinabersmart.earloft.R

/**
 * Umsortieren ohne Ziehen: Den Ziehgriff kann TalkBack nicht bedienen; die Zeile bietet deshalb „Nach oben“ und „Nach unten“
 * als eigene Aktionen an. [index] ist ihre Stelle unter [count] Zeilen.
 */
@Composable
fun Modifier.reorderActions(index: Int, count: Int, onMove: (from: Int, to: Int) -> Unit): Modifier {
    val up = stringResource(R.string.action_move_up)
    val down = stringResource(R.string.action_move_down)
    return semantics {
        customActions = buildList {
            if (index > 0) add(CustomAccessibilityAction(up) { onMove(index, index - 1); true })
            if (index in 0 until count - 1) add(CustomAccessibilityAction(down) { onMove(index, index + 1); true })
        }
    }
}
