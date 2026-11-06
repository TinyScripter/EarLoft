package de.kleinabersmart.tinyamp.ui.spoken

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.di.ApplicationScope
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExporter
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastSourceSwitch
import de.kleinabersmart.tinyamp.core.data.podcast.SourceCheck
import de.kleinabersmart.tinyamp.core.model.PodcastFeed
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.ardSoundsPageUrl
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.ardSoundsShowId
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.parseArdSoundsInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Ablauf von „Quelle wechseln“ (Konzept 12.10). */
sealed interface SourceSwitchState {
    data object Idle : SourceSwitchState
    data object Checking : SourceSwitchState
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : SourceSwitchState

    /** Geprüft; wartet auf die Bestätigung. */
    data class Ready(val episodes: Int, val matched: Int, val keptUnmatched: Int) : SourceSwitchState
    data object Switching : SourceSwitchState
    data object Done : SourceSwitchState
}

/** Ergebnis des Datenbank-Exports im Dialog; `null` = fehlgeschlagen. */
data class SwitchExport(val result: DatabaseExportResult?)

@HiltViewModel(assistedFactory = SourceSwitchViewModel.Factory::class)
class SourceSwitchViewModel @AssistedInject constructor(
    @Assisted private val feedId: String,
    @param:ApplicationContext private val context: Context,
    private val sourceSwitch: PodcastSourceSwitch,
    private val databaseExporter: DatabaseExporter,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<SourceSwitchState>(SourceSwitchState.Idle)
    val state: StateFlow<SourceSwitchState> = _state

    private val _export = MutableStateFlow<SwitchExport?>(null)
    val export: StateFlow<SwitchExport?> = _export

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    private var checked: SourceCheck.Ready? = null

    /** Lädt die neue Quelle und ordnet die Folgen zu; geändert wird noch nichts. */
    fun check(input: String) {
        _state.value = SourceSwitchState.Checking
        viewModelScope.launch {
            _state.value = when (val result = sourceSwitch.check(feedId, input.trim())) {
                is SourceCheck.Ready -> {
                    checked = result
                    SourceSwitchState.Ready(result.episodes, result.matched, result.keptUnmatched)
                }
                SourceCheck.SameSource -> SourceSwitchState.Failed(R.string.podcast_source_switch_same)
                is SourceCheck.Taken -> SourceSwitchState.Failed(R.string.podcast_source_switch_taken, result.title)
                is SourceCheck.Failed -> SourceSwitchState.Failed(result.result.message)
            }
        }
    }

    /** Zurück zur Eingabe. */
    fun back() {
        checked = null
        _state.value = SourceSwitchState.Idle
    }

    /** Führt den geprüften Wechsel aus; im App-Scope, damit er nicht halb stehen bleibt, wenn die Seite geht. */
    fun switch() {
        val ready = checked ?: return
        _state.value = SourceSwitchState.Switching
        viewModelScope.launch {
            val switched = applicationScope.async { sourceSwitch.switch(ready) }.await()
            checked = null
            _state.value = if (switched) SourceSwitchState.Done else SourceSwitchState.Failed(R.string.podcast_source_switch_changed)
        }
    }

    /** Sicherung vor dem Wechsel: dieselbe Datei wie Einstellungen → Podcasts → „Datenbank exportieren“. */
    fun exportDatabase(uri: Uri) {
        _exporting.value = true
        viewModelScope.launch {
            val file = File(context.cacheDir, "export.db")
            val result = try {
                databaseExporter.export(file).also {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: throw IOException("Datei nicht beschreibbar")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Datenbank-Export fehlgeschlagen", e)
                null
            } catch (e: SQLiteException) {
                Log.w(TAG, "Datenbank-Export fehlgeschlagen", e)
                null
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
            _export.value = SwitchExport(result)
            _exporting.value = false
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): SourceSwitchViewModel
    }

    private companion object {
        const val TAG = "SourceSwitch"
    }
}

/**
 * Dialog „Quelle wechseln“: Warnung mit Datenbank-Export, neue Adresse, Prüfen, Bestätigen. Hat das Abo heute die Adresse
 * einer RSS-Bridge für ARD Sounds, steht die Sendung in ARD Sounds schon im Feld.
 */
@Composable
fun SourceSwitchDialog(
    feed: PodcastFeed,
    onDismiss: () -> Unit,
    onSwitched: () -> Unit,
    viewModel: SourceSwitchViewModel = hiltViewModel<SourceSwitchViewModel, SourceSwitchViewModel.Factory>(key = "source-switch-${feed.id}") {
        it.create(feed.id)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(suggestedSource(feed.url)) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportDatabase(uri)
    }

    LaunchedEffect(state) {
        if (state == SourceSwitchState.Done) onSwitched()
    }

    when (val current = state) {
        is SourceSwitchState.Ready, SourceSwitchState.Switching -> {
            val ready = current as? SourceSwitchState.Ready
            AlertDialog(
                onDismissRequest = { if (ready != null) viewModel.back() },
                title = { Text(stringResource(R.string.podcast_source_switch_confirm_title)) },
                text = {
                    if (ready == null) {
                        Working(R.string.podcast_source_switch_switching)
                    } else {
                        Column {
                            Text(stringResource(R.string.podcast_source_switch_matched, ready.matched, ready.episodes))
                            if (ready.keptUnmatched > 0) {
                                Text(
                                    stringResource(R.string.podcast_source_switch_kept, ready.keptUnmatched),
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::switch, enabled = ready != null) {
                        Text(stringResource(R.string.podcast_source_switch_do))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::back, enabled = ready != null) { Text(stringResource(R.string.podcast_source_switch_back)) }
                },
            )
        }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.podcast_source_switch)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.podcast_source_switch_warning), style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                onClick = { exportLauncher.launch("tinyamp-podcasts.db") },
                                enabled = !exporting,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text(stringResource(R.string.settings_db_export)) }
                            export?.let {
                                val result = it.result
                                Text(
                                    if (result == null) {
                                        stringResource(R.string.settings_db_export_failed)
                                    } else {
                                        stringResource(R.string.settings_db_export_done, result.feeds, result.episodes)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.podcast_source_switch_current, shownSource(feed.url)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.podcast_source_switch_new)) },
                        supportingText = { Text(stringResource(R.string.podcast_source_switch_new_hint)) },
                        isError = current is SourceSwitchState.Failed,
                        enabled = current != SourceSwitchState.Checking,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    when (current) {
                        SourceSwitchState.Checking -> Working(R.string.podcast_source_switch_checking)
                        is SourceSwitchState.Failed -> Text(
                            current.detail?.let { stringResource(current.message, it) } ?: stringResource(current.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.check(input) }, enabled = input.isNotBlank() && current != SourceSwitchState.Checking) {
                    Text(stringResource(R.string.podcast_source_switch_check))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Working(@StringRes text: Int) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(text), modifier = Modifier.padding(start = 12.dp))
    }
}

/** Sendungen aus ARD Sounds zeigen ihre Seite statt der internen Abo-Adresse. */
private fun shownSource(url: String): String = ardSoundsShowId(url)?.let(::ardSoundsPageUrl) ?: url

/** Vorschlag: Ist das Abo der Feed einer RSS-Bridge für ARD Sounds, die Sendung selbst; sonst leer. */
private fun suggestedSource(url: String): String =
    if (ardSoundsShowId(url) != null) "" else parseArdSoundsInput(url)?.let { ardSoundsShowId(it) }?.let(::ardSoundsPageUrl).orEmpty()
