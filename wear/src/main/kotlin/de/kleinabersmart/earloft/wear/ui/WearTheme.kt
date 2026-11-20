package de.kleinabersmart.earloft.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

/**
 * Theme of the watch app: Wear Material 3 with hyphenation in every text style. Without it, words that do not fit the
 * line on a small watch with a large font are split between arbitrary letters ("Einschlaf-Time / r"). Android hyphenates
 * by the app language's pattern; a language without a pattern on the device (e.g. Finnish) still breaks anywhere.
 */
@Composable
fun WearTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = remember(base) { base.withHyphenation() }
    MaterialTheme(typography = typography, content = content)
}

internal fun Typography.withHyphenation(): Typography = copy(
    displayLarge = displayLarge.hyphenated(),
    displayMedium = displayMedium.hyphenated(),
    displaySmall = displaySmall.hyphenated(),
    titleLarge = titleLarge.hyphenated(),
    titleMedium = titleMedium.hyphenated(),
    titleSmall = titleSmall.hyphenated(),
    labelLarge = labelLarge.hyphenated(),
    labelMedium = labelMedium.hyphenated(),
    labelSmall = labelSmall.hyphenated(),
    bodyLarge = bodyLarge.hyphenated(),
    bodyMedium = bodyMedium.hyphenated(),
    bodySmall = bodySmall.hyphenated(),
    bodyExtraSmall = bodyExtraSmall.hyphenated(),
)

/** `Paragraph` breaks lines with the high-quality strategy, which hyphenation needs to pick good break points. */
private fun TextStyle.hyphenated(): TextStyle = copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
