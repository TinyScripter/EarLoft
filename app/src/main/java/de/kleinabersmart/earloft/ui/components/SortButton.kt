package de.kleinabersmart.earloft.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Sort icon (for the app header); a tap opens the selection, the chosen order has a check mark. */
@Composable
fun <T> SortButton(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) = ChoiceButton(selected, options, label, DesignR.drawable.ic_sort, stringResource(R.string.sort_label), onSelect, modifier)

/**
 * Icon button (for the app header) with a selection menu like [SortButton]; [icon] shows the button, [title] plus the chosen
 * option is read out by TalkBack.
 */
@Composable
fun <T> ChoiceButton(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    @DrawableRes icon: Int,
    title: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(icon), contentDescription = "$title: ${label(selected)}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        if (option == selected) Icon(painterResource(DesignR.drawable.ic_check), contentDescription = null)
                    },
                )
            }
        }
    }
}
