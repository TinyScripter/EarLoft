package keinabersmart.kleinaberlaut.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.ui.library.BackButton

/** Alle Abhängigkeiten der App mit Lizenz; die Liste erzeugt das AboutLibraries-Plugin beim Build. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.licenses_title)) },
            navigationIcon = { BackButton(onBack) },
        )
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        )
    }
}
