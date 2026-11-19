package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.LocalDarkTheme

/**
 * Wide logo with wordmark, matching the app's theme. Chosen via [LocalDarkTheme] instead of `drawable-night`:
 * the in-app setting may differ from the system.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Image(
        painter = painterResource(if (LocalDarkTheme.current) R.drawable.logo_wide else R.drawable.logo_wide_light),
        contentDescription = stringResource(R.string.app_name),
        contentScale = contentScale,
        modifier = modifier,
    )
}
