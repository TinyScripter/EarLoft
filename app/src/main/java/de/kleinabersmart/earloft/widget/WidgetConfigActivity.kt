package de.kleinabersmart.earloft.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.ui.settings.areaTitle
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import de.kleinabersmart.earloft.ui.theme.ThemeSettings
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Einstellungen eines Widgets beim Anlegen und später über „Widget anpassen“ des Launchers (Notiz 8.14): Bereiche und
 * Farben ([WidgetConfig]). Zurück bzw. „Abbrechen“ beim Anlegen entfernt das Widget wieder (so will es Android).
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var themeSettings: ThemeSettings
    @Inject lateinit var spokenSettings: SpokenSettings

    private val appWidgetId: Int
        get() = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, result())
        // Die Activity ist exportiert (der Launcher startet sie): Eine fremde oder ungültige Kennung ließ Glance werfen, und der
        // Absturz nahm die Wiedergabe im selben Prozess mit (Review D29).
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || provider?.packageName != packageName) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val themeMode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = null)
            val systemColors by themeSettings.systemColors.collectAsStateWithLifecycle(initialValue = null)
            val areas by spokenSettings.areas.collectAsStateWithLifecycle(initialValue = null)
            val saved by produceState<SavedConfig?>(null) { value = SavedConfig(savedConfig()) }
            // Bis alles gelesen ist (wenige Millisekunden): nichts zeichnen statt kurz falsch.
            val mode = themeMode ?: return@setContent
            val enabledAreas = areas ?: return@setContent
            val loaded = saved ?: return@setContent
            val darkTheme = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            EarLoftTheme(darkTheme = darkTheme, systemColors = systemColors ?: false) {
                WidgetConfigScreen(
                    availableAreas = enabledAreas,
                    initial = loaded.config ?: WidgetConfig(areas = enabledAreas.toSet()),
                    onDone = ::save,
                    onCancel = ::finish,
                )
            }
        }
    }

    private class SavedConfig(val config: WidgetConfig?)

    /** Beim Anpassen die bisherigen Einstellungen; beim Anlegen `null`. */
    private suspend fun savedConfig(): WidgetConfig? {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        return getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId).widgetConfig()
    }

    private fun save(config: WidgetConfig) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { it.setWidgetConfig(config) }
            NowPlayingWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, result())
            finish()
        }
    }

    private fun result(): Intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

/**
 * Bereiche (nur die in der App eingeschalteten; mindestens einer) und Farben. Bereiche, die in der App ausgeschaltet sind,
 * bleiben beim Anpassen so gewählt, wie sie waren.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    availableAreas: List<SpokenMode>,
    initial: WidgetConfig,
    onDone: (WidgetConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var areas by rememberSaveable { mutableStateOf(initial.areas.toList()) }
    var colors by rememberSaveable { mutableStateOf(initial.colors) }
    val shownAreas = areas.filter { it in availableAreas }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onDone(WidgetConfig(areas.toSet(), colors)) },
                    enabled = shownAreas.isNotEmpty(),
                ) { Text(stringResource(R.string.widget_config_done)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(R.string.widget_config_areas)
            Text(
                stringResource(R.string.widget_config_areas_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            availableAreas.forEach { area ->
                val checked = area in areas
                ListItem(
                    headlineContent = { Text(stringResource(areaTitle(area))) },
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) { on ->
                        areas = if (on) areas + area else areas - area
                    },
                )
            }
            SectionTitle(R.string.widget_config_colors)
            WidgetColors.entries
                // Material You gibt es erst ab Android 12.
                .filter { it != WidgetColors.SYSTEM || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
                .forEach { option ->
                    val selected = option == colors
                    ListItem(
                        headlineContent = { Text(stringResource(option.label)) },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { colors = option },
                    )
                }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

private val WidgetColors.label: Int
    get() = when (this) {
        WidgetColors.COVER -> R.string.widget_colors_cover
        WidgetColors.APP -> R.string.widget_colors_app
        WidgetColors.SYSTEM -> R.string.widget_colors_system
    }
