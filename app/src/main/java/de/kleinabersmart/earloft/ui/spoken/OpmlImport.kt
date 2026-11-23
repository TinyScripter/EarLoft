package de.kleinabersmart.earloft.ui.spoken

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.OpmlImportResult
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.network.podcast.Opml
import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** OPML import for the settings and the "Add podcast" page. */
class OpmlImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val podcasts: PodcastRepository,
    private val refresher: PodcastRefresher,
) {
    /**
     * Reads the subscriptions from [uri] and adopts them ([replace]: all other subscriptions are dropped); afterwards the new feeds
     * are synced. `null` if the file isn't readable or isn't OPML.
     */
    suspend fun import(uri: Uri, replace: Boolean): OpmlImportResult? = try {
        podcasts.importOpml(read(uri), replace).also { if (it.added > 0) refresher.refreshAll() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "OPML import failed", e)
        null
    }

    /** How many subscriptions importing [uri] with "replace" would remove; `null` if the file isn't readable or isn't OPML. */
    suspend fun replaceRemovals(uri: Uri): Int? = try {
        podcasts.opmlReplaceRemovals(read(uri))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "OPML file not readable", e)
        null
    }

    private suspend fun read(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use(Opml::parse) ?: throw IOException("File not readable")
    }

    private companion object {
        const val TAG = "OpmlImporter"
    }
}

/** Message after an OPML import; [result] `null` = failed. */
fun opmlImportMessage(resources: Resources, result: OpmlImportResult?): String =
    if (result == null) {
        resources.getString(R.string.settings_opml_import_failed)
    } else {
        resources.getQuantityString(R.plurals.settings_opml_imported, result.added, result.added, result.skipped, result.removed)
    }

/**
 * Asks before the import whether the subscriptions are added or replaced. Replacing can't be undone: the dialog names how many
 * subscriptions it removes ([countRemovals], `null` = unknown) and shows it in red (review J51).
 */
@Composable
fun OpmlImportDialog(countRemovals: suspend () -> Int?, onImport: (replace: Boolean) -> Unit, onDismiss: () -> Unit) {
    val removals by produceState<Int?>(null) { value = countRemovals() }
    val message = when (val count = removals) {
        null -> stringResource(R.string.settings_opml_import_message)
        0 -> stringResource(R.string.settings_opml_import_question) + " " + stringResource(R.string.settings_opml_replace_none)
        else -> stringResource(R.string.settings_opml_import_question) + " " +
            pluralStringResource(R.plurals.settings_opml_replace_removes, count, count)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_opml_import)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onImport(false) }) { Text(stringResource(R.string.settings_opml_add)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                DestructiveTextButton(onClick = { onImport(true) }) { Text(stringResource(R.string.settings_opml_replace)) }
            }
        },
    )
}
