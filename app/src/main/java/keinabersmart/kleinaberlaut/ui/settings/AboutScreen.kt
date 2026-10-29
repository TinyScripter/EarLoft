package keinabersmart.kleinaberlaut.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.library.BackButton

/** Infoseite über die App mit Links zum Projekt und zum Issue-Tracker. */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionName = remember(context) { context.versionName() }
    AboutContent(versionName = versionName, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutContent(versionName: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.about_title)) },
            navigationIcon = { BackButton(onBack) },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_wide),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(vertical = 16.dp),
            )
            if (versionName != null) {
                Text(
                    stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.about_text),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            LinkBlock(R.string.about_more_info, R.string.about_repository_url)
            LinkBlock(R.string.about_feedback, R.string.about_issues_url)
        }
    }
}

/** Erklärtext mit darunter stehendem, anklickbarem Link. */
@Composable
private fun LinkBlock(@StringRes labelRes: Int, @StringRes urlRes: Int) {
    val url = stringResource(urlRes)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = buildAnnotatedString {
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(
                            SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
                        ),
                    ),
                ) { append(url) }
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Context.versionName(): String? =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

@Preview(showBackground = true)
@Composable
private fun AboutContentPreview() {
    KleinaberlautTheme {
        AboutContent(versionName = "1.0", onBack = {})
    }
}
