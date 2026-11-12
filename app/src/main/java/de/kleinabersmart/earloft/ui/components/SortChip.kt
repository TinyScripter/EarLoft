package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Zeigt die gewählte Reihenfolge; ein Tipp öffnet die Auswahl (die gewählte mit Haken). */
@Composable
fun <T> SortChip(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val sortLabel = stringResource(R.string.sort_label)
    val current = label(selected)
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text(current) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_sort), contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.semantics { contentDescription = "$sortLabel: $current" },
        )
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
