package keinabersmart.kleinaberlaut.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.sync.SyncNotice
import keinabersmart.kleinaberlaut.core.data.sync.SyncNotices
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

@HiltViewModel
class SyncNoticeViewModel @Inject constructor(notices: SyncNotices) : ViewModel() {
    val notices: SharedFlow<SyncNotice> = notices.notices
}

/** Zeigt Hinweise des Abgleichs (Konflikte, verworfene Änderungen) als Toast, solange die App sichtbar ist. */
@Composable
fun SyncNoticeEffect(viewModel: SyncNoticeViewModel = hiltViewModel()) {
    val context = LocalContext.current
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
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
