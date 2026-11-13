package de.kleinabersmart.earloft.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R

/** Die Alben eines Jahrzehnts nach Jahr, darüber „Abspielen“ und „Zufällig“ für alle ihre Songs. */
@Composable
fun DecadeScreen(
    startYear: Int,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DecadeViewModel = hiltViewModel<DecadeViewModel, DecadeViewModel.Factory> { it.create(startYear) },
) {
    val state by viewModel.albums.collectAsStateWithLifecycle()
    AlbumCollectionScreen(
        title = decadeLabel(startYear, short = false),
        state = state,
        onBack = onBack,
        onAlbumClick = onAlbumClick,
        onPlay = viewModel::play,
        modifier = modifier,
        scrollLabel = { album -> album.year?.toString().orEmpty() },
    )
}

/** „80er“ bzw. „1980er“ ([short] kürzt nur 1910–1999). */
@Composable
internal fun decadeLabel(startYear: Int, short: Boolean): String =
    stringResource(R.string.decade, if (short && startYear in 1910..1999) startYear - 1900 else startYear)
