package de.kleinabersmart.earloft.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.ShareReceiverActivity
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.share.ShareContent
import de.kleinabersmart.earloft.core.data.share.ShareRepository
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Sharing via the system (concept 13): a link to a public directory, never to our own servers. [shareMedia],
 * by contrast, passes on the downloaded audio file of an episode.
 */
@Stable
class ShareController(val share: (ShareTarget) -> Unit, val shareMedia: (bookId: Long, title: String) -> Unit)

/** `null` (preview, tests): no sharing. */
val LocalShareController = staticCompositionLocalOf<ShareController?> { null }

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val repository: ShareRepository,
    private val downloads: EpisodeDownloads,
    private val snackbars: Snackbars,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val copying = Mutex()

    private val _readyMedia = Channel<Pair<SharedMedia, String>>(Channel.BUFFERED)

    /** Copies for [prepareMedia] that are ready to share, with their title; kept for the next collector after a rotation. */
    val readyMedia: Flow<Pair<SharedMedia, String>> = _readyMedia.receiveAsFlow()

    /** Like [media], in the view model's scope: turning the device while copying must not cancel it (review2 J75). */
    fun prepareMedia(bookId: Long, title: String) {
        viewModelScope.launch { media(bookId, title)?.let { _readyMedia.send(it to title) } }
    }

    suspend fun content(target: ShareTarget): ShareContent? = repository.content(target)

    /**
     * The downloaded episode as a file to share: a copy in the cache named after the episode title (the provider only sees there,
     * and the recipient sees a readable name). At most one copy is kept. `null` if the file is missing,
     * a copy is already being created (a second call would delete it mid-write) or copying fails (storage
     * full, file gone in the meantime; previously a crash, review D14).
     */
    suspend fun media(bookId: Long, title: String): SharedMedia? {
        if (!copying.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) { copyForSharing(bookId, title) }
        } finally {
            copying.unlock()
        }
    }

    private suspend fun copyForSharing(bookId: Long, title: String): SharedMedia? {
        val source = downloads.downloadedFile(bookId) ?: return null
        val directory = File(context.cacheDir, SHARED_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val name = title.replace(UNSAFE_FILE_NAME_CHARS, " ").trim().take(MAX_FILE_NAME).ifBlank { "episode" }
        val copy = File(directory, source.file.extension.let { if (it.isEmpty()) name else "$name.$it" })
        // Taken back once the copy is there, as the mix hint (review5 P62): the share sheet should not open under it.
        val preparing = SnackbarMessage(context.getString(R.string.share_media_preparing))
        if (source.file.length() > ANNOUNCE_COPY_BYTES) snackbars.show(preparing)
        try {
            source.file.copyTo(copy, overwrite = true)
        } catch (e: IOException) {
            Log.w(TAG, "Episode $bookId not copied for sharing", e)
            copy.delete()
            snackbars.withdraw(preparing)
            snackbars.show(context.getString(R.string.share_media_failed))
            return null
        }
        snackbars.withdraw(preparing)
        return SharedMedia(FileProvider.getUriForFile(context, "${context.packageName}.shared", copy), source.mimeType ?: "audio/*")
    }

    private companion object {
        const val TAG = "ShareViewModel"
        const val SHARED_DIR = "shared"

        /** From this size on, copying takes noticeably long: a short hint that it is in progress. */
        const val ANNOUNCE_COPY_BYTES = 20L * 1024 * 1024
        const val MAX_FILE_NAME = 80
        val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    }
}

/** A file provided for sharing. */
class SharedMedia(val uri: Uri, val mimeType: String)

@Composable
fun rememberShareController(viewModel: ShareViewModel = hiltViewModel()): ShareController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel, context) {
        viewModel.readyMedia.collect { (media, title) -> context.startShareMedia(media, title) }
    }
    return remember(viewModel, context, scope) {
        ShareController(
            share = { target -> scope.launch { viewModel.content(target)?.let(context::startShare) } },
            shareMedia = { bookId, title -> viewModel.prepareMedia(bookId, title) },
        )
    }
}

private fun Context.startShare(content: ShareContent) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, content.text)
        .putExtra(Intent.EXTRA_SUBJECT, content.subject)
        .putExtra(Intent.EXTRA_TITLE, content.subject)
    // EarLoft accepts shared text itself (add podcast); in its own share menu that would only be confusing.
    val chooser = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ShareReceiverActivity::class.java)))
    startActivity(chooser)
}

private fun Context.startShareMedia(media: SharedMedia, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(media.mimeType)
        .putExtra(Intent.EXTRA_STREAM, media.uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TITLE, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}

/** "Share" entry for the context menus; invisible without [LocalShareController]. */
@Composable
fun ShareMenuItem(target: ShareTarget, onDismiss: () -> Unit, withIcon: Boolean = true) {
    val controller = LocalShareController.current ?: return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_share)) },
        leadingIcon = if (withIcon) {
            { Icon(painterResource(DesignR.drawable.ic_share), contentDescription = null) }
        } else {
            null
        },
        onClick = {
            controller.share(target)
            onDismiss()
        },
    )
}

/** "Share media file" entry for a downloaded episode; invisible without [LocalShareController]. */
@Composable
fun ShareMediaMenuItem(bookId: Long, title: String, onDismiss: () -> Unit) {
    val controller = LocalShareController.current ?: return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_share_media)) },
        onClick = {
            controller.shareMedia(bookId, title)
            onDismiss()
        },
    )
}

/** "Share" button for the TopAppBar; invisible without [LocalShareController]. */
@Composable
fun ShareButton(target: ShareTarget) {
    val controller = LocalShareController.current ?: return
    IconButton(onClick = { controller.share(target) }) {
        Icon(painterResource(DesignR.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
    }
}
