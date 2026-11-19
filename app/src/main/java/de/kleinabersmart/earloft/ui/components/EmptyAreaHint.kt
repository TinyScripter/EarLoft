package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.SpokenMode

/**
 * Opens the setting that hides an area (General → Areas). `null` if only one area
 * is enabled – it can't be disabled – or outside Home and Library.
 */
val LocalOpenAreaSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Area without content: the hint [text], first the setup ([setupLabel], [onSetup]; omitted without them),
 * below it the way to the setting that hides [area] ([LocalOpenAreaSettings]).
 */
@Composable
fun EmptyAreaHint(
    area: SpokenMode,
    text: String,
    setupLabel: String?,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openAreaSettings = LocalOpenAreaSettings.current
    Column(modifier.fillMaxWidth().padding(24.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (setupLabel != null) {
            FilledTonalButton(onClick = onSetup, modifier = Modifier.padding(top = 12.dp)) {
                Text(setupLabel)
            }
        }
        if (openAreaSettings != null) {
            OutlinedButton(onClick = openAreaSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(hideAreaLabel(area)))
            }
        }
    }
}

/** "Hide music" etc. */
fun hideAreaLabel(area: SpokenMode): Int = when (area) {
    SpokenMode.MUSIC -> R.string.area_hide_music
    SpokenMode.PODCASTS -> R.string.area_hide_podcasts
    SpokenMode.BOOKS -> R.string.area_hide_books
}

@Preview(showBackground = true)
@Composable
private fun EmptyAreaHintPreview() {
    EarLoftTheme {
        CompositionLocalProvider(LocalOpenAreaSettings provides {}) {
            EmptyAreaHint(
                area = SpokenMode.BOOKS,
                text = stringResource(R.string.spoken_no_server),
                setupLabel = stringResource(R.string.spoken_setup),
                onSetup = {},
            )
        }
    }
}
