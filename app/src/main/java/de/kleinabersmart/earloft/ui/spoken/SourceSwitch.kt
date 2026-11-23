package de.kleinabersmart.earloft.ui.spoken

import android.net.Uri
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsPageUrl
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsShowId
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.parseArdSoundsInput
import de.kleinabersmart.earloft.ui.settings.databaseExportMessage
import kotlinx.coroutines.launch

/**
 * "Switch source" dialog: warning with database export, new address, check, confirm. If the subscription currently has the address
 * of an RSS bridge for ARD Sounds, the show on ARD Sounds is already in the field.
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

    val dismiss = {
        viewModel.reset()
        onDismiss()
    }
    LaunchedEffect(state) {
        if (state == SourceSwitchState.Done) {
            viewModel.reset()
            onSwitched()
        }
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
                            // Which address it switches to (review2 J10).
                            Text(
                                ready.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            Text(
                                stringResource(
                                    R.string.podcast_source_switch_matched,
                                    ready.matched,
                                    pluralStringResource(R.plurals.count_episodes, ready.episodes, ready.episodes),
                                ),
                            )
                            if (ready.keptUnmatched > 0) {
                                Text(
                                    pluralStringResource(R.plurals.podcast_source_switch_kept, ready.keptUnmatched, ready.keptUnmatched),
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
            onDismissRequest = dismiss,
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
                                onClick = { exportLauncher.launch("earloft-podcasts.db") },
                                enabled = !exporting,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text(stringResource(R.string.settings_db_export)) }
                            export?.let {
                                Text(databaseExportMessage(LocalResources.current, it.result), style = MaterialTheme.typography.bodySmall)
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
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
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

/** Shows from ARD Sounds display their page instead of the internal subscription address. */
private fun shownSource(url: String): String = ardSoundsShowId(url)?.let(::ardSoundsPageUrl) ?: url

/** Suggestion: if the subscription is the feed of an RSS bridge for ARD Sounds, the show itself; otherwise empty. */
private fun suggestedSource(url: String): String =
    if (ardSoundsShowId(url) != null) "" else parseArdSoundsInput(url)?.let { ardSoundsShowId(it) }?.let(::ardSoundsPageUrl).orEmpty()
