package de.kleinabersmart.tinyamp.ui.settings

import android.content.Context
import android.content.res.Resources
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.di.ApplicationScope
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * „Datenbank exportieren“ aus den Einstellungen und vor dem Wechsel der Quelle eines Abos: dieselbe Datei. Läuft im App-Scope zu
 * Ende, auch wenn die Seite vorher geht; die temporäre Datei gehört dem Auftrag (je Export eine eigene).
 */
@Singleton
class DatabaseExport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exporter: DatabaseExporter,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    /** @return was geschrieben wurde; `null`, wenn der Export scheiterte. */
    suspend fun export(uri: Uri): DatabaseExportResult? = try {
        applicationScope.async {
            val file = withContext(Dispatchers.IO) { File.createTempFile("export", ".db", context.cacheDir) }
            try {
                exporter.export(file).also {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: throw IOException("Datei nicht beschreibbar")
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
        }.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Datenbank-Export fehlgeschlagen", e)
        null
    } catch (e: SQLiteException) {
        Log.w(TAG, "Datenbank-Export fehlgeschlagen", e)
        null
    }

    private companion object {
        const val TAG = "DatabaseExport"
    }
}

/** Meldung nach dem Export (Einstellungen und Quellwechsel); [result] `null` = fehlgeschlagen. */
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
