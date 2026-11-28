package de.kleinabersmart.earloft.ui.settings

import android.content.Context
import android.content.res.Resources
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.earloft.core.data.podcast.DatabaseExporter
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Export database" from the settings and before switching a subscription's source: the same file. Runs to completion in the app scope,
 * even if the page goes away first; the temporary file belongs to the job (a separate one per export).
 */
@Singleton
class DatabaseExport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exporter: DatabaseExporter,
    private val podcastSettings: PodcastSettings,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    /** @return what was written; `null` if the export failed. */
    suspend fun export(uri: Uri): DatabaseExportResult? = try {
        applicationScope.async {
            val file = withContext(Dispatchers.IO) { File.createTempFile("export", ".db", context.cacheDir) }
            try {
                exporter.export(file, podcastSettings.rssBridge.first().ifBlank { null }).also {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: throw IOException("File not writable")
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
        }.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Database export failed", e)
        null
    } catch (e: SQLiteException) {
        Log.w(TAG, "Database export failed", e)
        null
    } catch (e: RuntimeException) {
        // A `SecurityException` or `IllegalStateException` from the content resolver as well (review5 P44).
        Log.w(TAG, "Database export failed", e)
        null
    }

    private companion object {
        const val TAG = "DatabaseExport"
    }
}

/** Message after the export (settings and source switch); [result] `null` = failed. */
fun databaseExportMessage(resources: Resources, result: DatabaseExportResult?): String =
    if (result == null) {
        resources.getString(R.string.settings_db_export_failed)
    } else {
        resources.getString(
            R.string.settings_db_export_done,
            resources.getQuantityString(R.plurals.count_podcasts, result.feeds, result.feeds),
            resources.getQuantityString(R.plurals.count_episodes, result.episodes, result.episodes),
        )
    }
