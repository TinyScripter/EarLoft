package de.kleinabersmart.tinyamp.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.MainActivity
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.share.ShareContent
import de.kleinabersmart.tinyamp.core.data.share.ShareRepository
import de.kleinabersmart.tinyamp.core.data.share.ShareTarget
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

/** Teilen über das System (Konzept 13): Link auf ein öffentliches Verzeichnis, nie auf die eigenen Server. */
@Stable
class ShareController(val share: (ShareTarget) -> Unit)

/** `null` (Vorschau, Tests): kein Teilen. */
val LocalShareController = staticCompositionLocalOf<ShareController?> { null }

@HiltViewModel
class ShareViewModel @Inject constructor(private val repository: ShareRepository) : ViewModel() {
    suspend fun content(target: ShareTarget): ShareContent? = repository.content(target)
}

@Composable
fun rememberShareController(viewModel: ShareViewModel = hiltViewModel()): ShareController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(viewModel, context, scope) {
        ShareController { target ->
            scope.launch { viewModel.content(target)?.let(context::startShare) }
        }
    }
}

private fun Context.startShare(content: ShareContent) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, content.text)
        .putExtra(Intent.EXTRA_SUBJECT, content.subject)
        .putExtra(Intent.EXTRA_TITLE, content.subject)
    // TinyAmp nimmt geteilten Text selbst an (Podcast hinzufügen); im eigenen Teilen-Menü wäre das nur verwirrend.
    val chooser = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
    startActivity(chooser)
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

/** Knopf „Teilen“ für die TopAppBar; ohne [LocalShareController] unsichtbar. */
@Composable
fun ShareButton(target: ShareTarget) {
    val controller = LocalShareController.current ?: return
    IconButton(onClick = { controller.share(target) }) {
        Icon(painterResource(DesignR.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
    }
}
