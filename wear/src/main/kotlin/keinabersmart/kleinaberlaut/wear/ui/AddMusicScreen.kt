package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.sync.PhoneStatus

/**
 * „Musik hinzufügen“ (Wear-Konzept 11): Favoriten und Playlists des Handys. Das Handy löst die Auswahl auf;
 * ohne verbundenes Handy sind die Schalter deaktiviert.
 */
@Composable
fun AddMusicScreen(viewModel: AddMusicViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    AddMusicContent(state, onToggle = viewModel::setSelected)
}

@Composable
private fun AddMusicContent(state: AddMusicUiState, onToggle: (CatalogItem, Boolean) -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.add_music_title), textAlign = TextAlign.Center)
                }
            }
            if (!state.canChange) {
                item {
                    Text(
                        text = stringResource(R.string.add_music_no_phone),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val catalog = state.items
            when {
                catalog == null -> if (state.canChange) item {
                    CircularProgressIndicator(Modifier.transformedHeight(this, transformationSpec))
                }
                else -> items(catalog, key = { it.key }) { entry ->
                    SwitchButton(
                        checked = entry.selected,
                        onCheckedChange = { onToggle(entry, it) },
                        enabled = state.canChange,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = {
                            Text(
                                entry.name ?: stringResource(R.string.add_music_favorites),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        secondaryLabel = {
                            Text(pluralStringResource(R.plurals.song_count, entry.songCount, entry.songCount))
                        },
                    )
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun AddMusicPreview() {
    MaterialTheme {
        AddMusicContent(
            state = AddMusicUiState(
                phone = PhoneStatus.Connected("phone"),
                items = listOf(
                    CatalogItem(WearSelectionKind.FAVORITES, null, null, 42, null, selected = true),
                    CatalogItem(WearSelectionKind.PLAYLIST, 1, "Laufen am Morgen", 25, null, selected = false),
                    CatalogItem(WearSelectionKind.PLAYLIST, 2, "Fitness", 60, "Navidrome", selected = true),
                ),
            ),
            onToggle = { _, _ -> },
        )
    }
}

@WearPreviewDevices
@Composable
private fun AddMusicNoPhonePreview() {
    MaterialTheme {
        AddMusicContent(AddMusicUiState(phone = PhoneStatus.NotConnected), onToggle = { _, _ -> })
    }
}
