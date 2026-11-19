package de.kleinabersmart.earloft.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.sync.SyncNotice
import de.kleinabersmart.earloft.core.data.sync.SyncNotices
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

@HiltViewModel
class SyncNoticeViewModel @Inject constructor(notices: SyncNotices) : ViewModel() {
    val notices: SharedFlow<SyncNotice> = notices.notices
}

/** Shows sync messages (conflicts, discarded changes) as a snackbar while the app is visible. */
@Composable
fun SyncNoticeEffect(viewModel: SyncNoticeViewModel = hiltViewModel()) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val text = when (notice) {
                is SyncNotice.PlaylistReplacedByServer ->
                    resources.getString(R.string.sync_notice_playlist_replaced, notice.playlistName)
                is SyncNotice.ChangeDropped -> resources.getString(
                    when (notice.kind) {
                        SyncNotice.ChangeDropped.Kind.FAVORITE -> R.string.sync_notice_favorite_dropped
                        SyncNotice.ChangeDropped.Kind.PLAYLIST -> R.string.sync_notice_playlist_dropped
                    },
                )
            }
            snackbars.show(text, SnackbarDuration.Long)
        }
    }
}
