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
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.OpmlImportResult
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.network.podcast.Opml
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
        val feeds = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use(Opml::parse) ?: throw IOException("Datei nicht lesbar")
        }
        podcasts.importOpml(feeds, replace).also { if (it.added > 0) refresher.refreshAll() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "OPML-Import fehlgeschlagen", e)
        null
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

/** Asks before the import whether the subscriptions are added or replaced. */
@Composable
fun OpmlImportDialog(onImport: (replace: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_opml_import)) },
        text = { Text(stringResource(R.string.settings_opml_import_message)) },
        confirmButton = {
            TextButton(onClick = { onImport(false) }) { Text(stringResource(R.string.settings_opml_add)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { onImport(true) }) { Text(stringResource(R.string.settings_opml_replace)) }
            }
        },
    )
}
