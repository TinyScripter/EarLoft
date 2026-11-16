package de.kleinabersmart.earloft.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.library.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Datenschutzerklärung aus `PRIVACY.md` bzw. `PRIVACY.de.md` im Projektroot; die Datei je Sprache nennt `privacy_asset`. */
@Composable
fun PrivacyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val asset = stringResource(R.string.privacy_asset)
    val blocks by produceState(emptyList<PolicyBlock>(), context, asset) {
        value = withContext(Dispatchers.IO) {
            parsePolicy(context.assets.open(asset).bufferedReader().use { it.readText() })
        }
    }
    PrivacyContent(blocks = blocks, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyContent(blocks: List<PolicyBlock>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val linkStyles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.privacy_title)) },
            navigationIcon = { BackButton(onBack) },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    // Die Hauptüberschrift steht schon in der App-Leiste.
                    is PolicyBlock.Heading -> if (block.level > 1) {
                        Text(
                            policyText(block.text, linkStyles),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is PolicyBlock.Paragraph -> Text(policyText(block.text, linkStyles), style = MaterialTheme.typography.bodyMedium)
                    is PolicyBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Text(policyText(block.text, linkStyles), style = MaterialTheme.typography.bodyMedium)
                    }
                    PolicyBlock.Divider -> HorizontalDivider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PrivacyContentPreview() {
    EarLoftTheme {
        PrivacyContent(
            blocks = parsePolicy(
                """
                # Privacy Policy for EarLoft

                **Last updated:** November 15, 2026

                ## 1. Data Collection and Tracking

                * **No analytics, ads or trackers:** The app contains no *tracking* SDKs (`READ_MEDIA_AUDIO`).

                ---

                See https://github.com/TinyScripter/EarLoft
                """.trimIndent(),
            ),
            onBack = {},
        )
    }
}
