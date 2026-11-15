package de.kleinabersmart.earloft.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.ShareReceiverActivity
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.share.ShareContent
import de.kleinabersmart.earloft.core.data.share.ShareRepository
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Teilen über das System (Konzept 13): Link auf ein öffentliches Verzeichnis, nie auf die eigenen Server. [shareMedia]
 * gibt dagegen die geladene Audiodatei einer Folge weiter.
 */
@Stable
class ShareController(val share: (ShareTarget) -> Unit, val shareMedia: (bookId: Long, title: String) -> Unit)

/** `null` (Vorschau, Tests): kein Teilen. */
val LocalShareController = staticCompositionLocalOf<ShareController?> { null }

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val repository: ShareRepository,
    private val downloads: EpisodeDownloads,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    suspend fun content(target: ShareTarget): ShareContent? = repository.content(target)

    /**
     * Die geladene Folge als Datei zum Teilen: eine Kopie im Cache unter dem Titel der Folge (der Provider sieht nur dort,
     * und der Empfänger sieht einen lesbaren Namen). Es bleibt höchstens eine Kopie liegen. `null`, wenn die Datei fehlt.
     */
    suspend fun media(bookId: Long, title: String): SharedMedia? = withContext(Dispatchers.IO) {
        val source = downloads.downloadedFile(bookId) ?: return@withContext null
        val directory = File(context.cacheDir, SHARED_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val name = title.replace(UNSAFE_FILE_NAME_CHARS, " ").trim().take(MAX_FILE_NAME).ifBlank { "episode" }
        val copy = File(directory, source.file.extension.let { if (it.isEmpty()) name else "$name.$it" })
        source.file.copyTo(copy, overwrite = true)
        SharedMedia(FileProvider.getUriForFile(context, "${context.packageName}.shared", copy), source.mimeType ?: "audio/*")
    }

    private companion object {
        const val SHARED_DIR = "shared"
        const val MAX_FILE_NAME = 80
        val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    }
}

/** Eine zum Teilen bereitgestellte Datei. */
class SharedMedia(val uri: Uri, val mimeType: String)

@Composable
fun rememberShareController(viewModel: ShareViewModel = hiltViewModel()): ShareController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(viewModel, context, scope) {
        ShareController(
            share = { target -> scope.launch { viewModel.content(target)?.let(context::startShare) } },
            shareMedia = { bookId, title -> scope.launch { viewModel.media(bookId, title)?.let { context.startShareMedia(it, title) } } },
        )
    }
}

private fun Context.startShare(content: ShareContent) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, content.text)
        .putExtra(Intent.EXTRA_SUBJECT, content.subject)
        .putExtra(Intent.EXTRA_TITLE, content.subject)
    // EarLoft nimmt geteilten Text selbst an (Podcast hinzufügen); im eigenen Teilen-Menü wäre das nur verwirrend.
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

/** Eintrag „Teilen“ für die Kontextmenüs; ohne [LocalShareController] unsichtbar. */
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

/** Eintrag „Mediendatei teilen“ für eine geladene Folge; ohne [LocalShareController] unsichtbar. */
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

/** Knopf „Teilen“ für die TopAppBar; ohne [LocalShareController] unsichtbar. */
@Composable
fun ShareButton(target: ShareTarget) {
    val controller = LocalShareController.current ?: return
    IconButton(onClick = { controller.share(target) }) {
        Icon(painterResource(DesignR.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
    }
}
