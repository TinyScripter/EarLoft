package de.kleinabersmart.earloft.ui.settings

import android.content.Context
import android.content.res.Resources
import android.database.SQLException
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.backup.BackupCounts
import de.kleinabersmart.earloft.core.data.backup.FullBackup
import de.kleinabersmart.earloft.core.data.backup.FullBackupException
import de.kleinabersmart.earloft.core.data.backup.FullBackupResult
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Ausgang einer Komplett-Sicherung bzw. ihres Einspielens. */
sealed interface FullBackupMessage {
    data class Exported(val counts: BackupCounts) : FullBackupMessage
    data object ExportFailed : FullBackupMessage
    data class Imported(val result: FullBackupResult) : FullBackupMessage

    /** [newerVersion]: Die Sicherung stammt aus einer neueren Version der App; sonst ist es keine (lesbare) Sicherung. */
    data class ImportFailed(val newerVersion: Boolean) : FullBackupMessage
}

/** Einstellungen → Allgemein → Sicherung ([FullBackup]). Beides läuft im App-Scope zu Ende, auch wenn die Seite vorher geht. */
@HiltViewModel
class FullBackupViewModel @Inject constructor(
    private val fullBackup: FullBackup,
    private val podcastRefresher: PodcastRefresher,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _messages = Channel<FullBackupMessage>(Channel.BUFFERED)

    /** Ergebnis, einmalig anzuzeigen. */
    val messages: Flow<FullBackupMessage> = _messages.receiveAsFlow()

    private var running = false

    fun export(uri: Uri) = run(failed = FullBackupMessage.ExportFailed) {
        val counts = applicationScope.async {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { fullBackup.export(it) }
                    ?: throw IOException("Datei nicht beschreibbar")
            }
        }.await()
        FullBackupMessage.Exported(counts)
    }

    fun import(uri: Uri) = run(failed = FullBackupMessage.ImportFailed(newerVersion = false)) {
        val result = applicationScope.async {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { fullBackup.import(it) }
                    ?: throw IOException("Datei nicht lesbar")
            }.also {
                // Neue Abos holen ihre Folgen gleich, nicht erst beim nächsten Öffnen der App; erst dann greift ihr Fortschritt.
                if (it.added.podcasts > 0) podcastRefresher.refreshAll()
            }
        }.await()
        FullBackupMessage.Imported(result)
    }

    /** Ein Auftrag zur Zeit; ein zweiter Tipp in der Zwischenzeit wird ignoriert. [failed]: Meldung bei Lese- und Schreibfehlern. */
    private fun run(failed: FullBackupMessage, block: suspend () -> FullBackupMessage) {
        if (running) return
        running = true
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FullBackupException) {
                Log.w(TAG, "Sicherung nicht eingespielt", e)
                FullBackupMessage.ImportFailed(e.newerVersion)
            } catch (e: IOException) {
                Log.w(TAG, "Sicherung fehlgeschlagen", e)
                failed
            } catch (e: SQLException) {
                Log.w(TAG, "Sicherung fehlgeschlagen", e)
                failed
            } finally {
                running = false
            }
            _messages.send(message)
        }
    }

    private companion object {
        const val TAG = "FullBackupViewModel"
    }
}

/** Text zu [message] für die Snackbar. */
fun fullBackupMessage(resources: Resources, message: FullBackupMessage): String = when (message) {
    is FullBackupMessage.Exported -> resources.getString(R.string.settings_backup_exported, message.counts.summary(resources))
    FullBackupMessage.ExportFailed -> resources.getString(R.string.settings_backup_export_failed)
    is FullBackupMessage.Imported -> buildString {
        append(resources.getString(R.string.settings_backup_imported, message.result.added.summary(resources)))
        if (message.result.localSourcesSkipped) append(' ').append(resources.getString(R.string.settings_backup_local_skipped))
    }
    is FullBackupMessage.ImportFailed -> resources.getString(
        if (message.newerVersion) R.string.settings_backup_import_newer else R.string.settings_backup_import_failed,
    )
}

private fun BackupCounts.summary(resources: Resources): String {
    val servers = musicServers + audiobookServers
    return listOf(
        resources.getQuantityString(R.plurals.settings_server_count, servers, servers),
        resources.getQuantityString(R.plurals.settings_podcast_count, podcasts, podcasts),
        resources.getQuantityString(R.plurals.count_playlists, playlists, playlists),
    ).joinToString(", ")
}
